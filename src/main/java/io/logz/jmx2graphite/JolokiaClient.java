package io.logz.jmx2graphite;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * @author amesika
 */
public class JolokiaClient extends MBeanClient {

    private static final Logger logger = LoggerFactory.getLogger(JolokiaClient.class);
    private String jolokiaFullURL;
    private int connectTimeout = (int) TimeUnit.SECONDS.toMillis(30);
    private int socketTimeout = (int) TimeUnit.SECONDS.toMillis(30);
    private CloseableHttpClient client;

    private ObjectMapper objectMapper;
    private Stopwatch stopwatch = Stopwatch.createUnstarted();

    public JolokiaClient(String jolokiaFullURL) {
        this.jolokiaFullURL = jolokiaFullURL;
        if (!jolokiaFullURL.endsWith("/")) {
            this.jolokiaFullURL = jolokiaFullURL + "/";
        }
        objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
              .setDefaultSocketConfig(SocketConfig.custom()
              .setSoTimeout(Timeout.ofMinutes(socketTimeout))
              .build())
              .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setSocketTimeout(Timeout.ofMinutes(socketTimeout))
                .setConnectTimeout(Timeout.ofMinutes(connectTimeout))
                .build())
              .build();
        client = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .build();
    }

    public List<MetricBean> getBeans() throws MBeanClientPollingFailure {
        try {
            stopwatch.reset().start();
            logger.debug("Retrieving /list of bean from Jolokia ({})...", jolokiaFullURL);

            ClassicHttpRequest httpGet = ClassicRequestBuilder.get(jolokiaFullURL + "list?canonicalNaming=false")
                .build();
            Map<String, Object> listResponse = client.execute(httpGet, response -> {
                if (response.getCode() != 200) {
                    throw new RuntimeException("Failed listing beans from jolokia. Response = " + new StatusLine(response).toString());
                }
                final HttpEntity responseEntity = response.getEntity();
                if (responseEntity == null) {
                    return null;
                }
                try (InputStream inputStream = responseEntity.getContent()) {
                    return objectMapper.readValue(inputStream, Map.class);
                }
            });

            // Map<String, Object> listResponse = objectMapper.readValue(response.returnContent().asStream(), Map.class);
            Map<String, Object> domains = (Map<String, Object>) listResponse.get("value");
            if (domains == null) {
                throw new RuntimeException("Response doesn't have value attribute expected from a list response");
            }
            return extractMetricsBeans(domains);
        } catch (IOException e) {
            throw new MBeanClientPollingFailure("Failed retrieving list of beans from Jolokia. Error = " + e.getMessage(), e);
        }
    }

    public List<MetricValue> getMetrics(List<MetricBean> beans) throws MBeanClientPollingFailure {
        List<JolokiaReadRequest> readRequests = Lists.newArrayList();
        for (MetricBean bean : beans) {
            readRequests.add(new JolokiaReadRequest(bean.getName(), bean.getAttributes()));
        }

        try {
            String requestBody = objectMapper.writeValueAsString(readRequests);
            if (logger.isTraceEnabled())
                logger.trace("Jolokia getBeans request body: {}", requestBody);
            
            ClassicHttpRequest httpPost = ClassicRequestBuilder.post(jolokiaFullURL + "read?ignoreErrors=true&canonicalNaming=false")
                .setEntity(requestBody,ContentType.APPLICATION_JSON)
                .build();
            ArrayList<Map<String, Object>> responses = client.execute(httpPost, repsonse -> {
                if (repsonse.getCode() != 200) {
                    throw new RuntimeException("Failed listing beans from jolokia. Response = " + new StatusLine(repsonse).toString());
                }
                final HttpEntity responseEntity = repsonse.getEntity();
                if (responseEntity == null) {
                    return null;
                }
                try (InputStream inputStream = responseEntity.getContent()) {

                    String responseBody = IOUtils.toString(inputStream, "UTF-8");
                    return objectMapper.readValue(responseBody, ArrayList.class);
                }
            });

            List<MetricValue> metricValues = Lists.newArrayList();
            for (Map<String, Object> resp : responses) {
                Map<String, Object> request = (Map<String, Object>) resp.get("request");
                String mbeanName = (String) request.get("mbean");
                int status = (int) resp.get("status");
                if (status != 200) {
                    String errMsg = "Failed reading mbean '" + mbeanName + "': " + status + " - " + resp.get("error");
                    if (logger.isDebugEnabled()) {
                        logger.debug(errMsg + ". Stacktrace = {}", resp.get("stacktrace"));
                    } else {
                        logger.warn(errMsg);
                    }
                    continue;
                }
                long metricTime = (long) ((Integer) resp.get("timestamp"));

                Map<String, Object> attrValues = (Map<String, Object>) resp.get("value");
                Map<String, Number> metricToValue = flatten(attrValues);
                for (String attrMetricName : metricToValue.keySet()) {
                    try {
                        metricValues.add(new MetricValue(
                                GraphiteClient.sanitizeMetricName(mbeanName, /* keepDot */ true) + "." + attrMetricName,
                                metricToValue.get(attrMetricName),
                                metricTime));
                    } catch (IllegalArgumentException e) {
                        logger.info("Can't sent Metric since it's invalid: " + e.getMessage());
                    }
                }
            }
            return metricValues;
        } catch (IOException e) {
            throw new MBeanClientPollingFailure("Failed reading beans from Jolokia. Error = " + e.getMessage(), e);
        }
    }

    private List<MetricBean> extractMetricsBeans(Map<String, Object> domains) {
        List<MetricBean> result = Lists.newArrayList();
        for (String domainName : domains.keySet()) {
            Map<String, Object> domain = (Map<String, Object>) domains.get(domainName);
            for (String mbeanName : domain.keySet()) {
                Map<String, Object> mbean = (Map<String, Object>) domain.get(mbeanName);
                Map<String, Object> attributes = (Map<String, Object>) mbean.get("attr");
                if (attributes != null) {
                    List<String> attrNames = new ArrayList<String>(attributes.keySet());
                    result.add(new MetricBean(domainName + ":" + mbeanName, attrNames));
                }
            }
        }
        return result;
    }

    private static Map<String, Number> flatten(Map<String, Object> attrValues) {
        Map<String, Number> metricValues = Maps.newHashMap();
        for (String key : attrValues.keySet()) {
            Object value = attrValues.get(key);
            if (value instanceof Map) {
                Map<String, Number> flattenValueTree = flatten((Map) value);

                for (String internalMetricName : flattenValueTree.keySet()) {
                    metricValues.put(
                            GraphiteClient.sanitizeMetricName(key, /* keepDot */ false) + "."
                                    + GraphiteClient.sanitizeMetricName(internalMetricName, /* keepDot */ false),
                            flattenValueTree.get(internalMetricName));
                }
            } else {
                if (value instanceof Number) {
                    metricValues.put(GraphiteClient.sanitizeMetricName(key, /* keepDot */ false), (Number) value);
                }
            }
        }
        return metricValues;
    }
}
