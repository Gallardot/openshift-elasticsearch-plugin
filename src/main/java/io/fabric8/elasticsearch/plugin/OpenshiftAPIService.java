/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.elasticsearch.plugin;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;


import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.rest.RestStatus;

import com.google.common.base.Joiner;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.jayway.jsonpath.JsonPath;

import io.fabric8.elasticsearch.plugin.model.Project;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

public class OpenshiftAPIService {
    
    private static final String ACCEPT = "Accept";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final Logger LOGGER = Loggers.getLogger(OpenshiftAPIService.class);
    private final OpenShiftClientFactory factory;
    private final long expiresInMillis;
    private Cache<String, String> userNameCache;
    private Cache<String,Set<Project>> userProjectCache;
    private Cache<String,Boolean> userSARCache;

    public OpenshiftAPIService(long expiresInMillis) {
        this(expiresInMillis, new OpenShiftClientFactory(){});
    }
    
    public OpenshiftAPIService(long expiresInMillis, OpenShiftClientFactory factory) {
        this.factory = factory;
        this.expiresInMillis = expiresInMillis;
        createCache();
    }

    private void createCache() {
        userNameCache = CacheBuilder.newBuilder()
                .expireAfterWrite(this.expiresInMillis, TimeUnit.MILLISECONDS)
                .removalListener(new RemovalListener<String, String>() {
                    @Override
                    public void onRemoval(RemovalNotification<String, String> notification) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Clear userNameCache for {} due to {}", notification.getKey(), notification.getCause());
                        }
                    }
                }).build();

        userProjectCache = CacheBuilder.newBuilder()
                .expireAfterWrite(this.expiresInMillis, TimeUnit.MILLISECONDS)
                .removalListener(new RemovalListener<String, Set<Project>>() {
                    @Override
                    public void onRemoval(RemovalNotification<String, Set<Project>> notification) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Clear userProjectCache for {} due to {}", notification.getKey(), notification.getCause());
                        }
                    }
                }).build();

        userSARCache = CacheBuilder.newBuilder()
                .expireAfterWrite(this.expiresInMillis, TimeUnit.MILLISECONDS)
                .removalListener(new RemovalListener<String, Boolean>() {
                    @Override
                    public void onRemoval(RemovalNotification<String, Boolean> notification) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Clear userSARCache for {} due to {}", notification.getKey(), notification.getCause());
                        }
                    }
                }).build();
    }
    
    public String userName(final String token) {
        try {
            return userNameCache.get(token, new Callable<String>() {
                @Override
                public String call() throws Exception {
                    Response response = null;
                    try (DefaultOpenShiftClient client = factory.buildClient(token)) {
                        Request okRequest = new Request.Builder()
                                .url(client.getMasterUrl() + "apis/user.openshift.io/v1/users/~")
                                .header(ACCEPT, APPLICATION_JSON)
                                .build();
                        response = client.getHttpClient().newCall(okRequest).execute();
                        final String body = response.body().string();
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Response: code '{}' {}", response.code(), body);
                        }
                        if (response.code() != RestStatus.OK.getStatus()) {
                            throw new ElasticsearchSecurityException("Unable to determine username from the token provided", RestStatus.fromCode(response.code()));
                        }
                        return JsonPath.read(body, "$.metadata.name");
                    } catch (IOException e) {
                        LOGGER.error("Error retrieving username from token", e);
                        throw new ElasticsearchException(e);
                    }
                }
            });
        } catch (ExecutionException e) {
            throw new ElasticsearchException(e);
        }
    }
    
    public Set<Project> projectNames(final String token){
        try {
            return userProjectCache.get(token, new Callable<Set<Project>>() {
                @Override
                public Set<Project> call() throws Exception {
                    try (DefaultOpenShiftClient client = factory.buildClient(token)) {
                        Request request = new Request.Builder()
                                .url(client.getMasterUrl() + "apis/project.openshift.io/v1/projects")
                                .header(ACCEPT, APPLICATION_JSON)
                                .build();
                        Response response = client.getHttpClient().newCall(request).execute();
                        if(response.code() != RestStatus.OK.getStatus()) {
                            throw new ElasticsearchSecurityException("Unable to retrieve users's project list", RestStatus.fromCode(response.code()));
                        }
                        Set<Project> projects = new HashSet<>();
                        List<Map<String, String>> raw = JsonPath.read(response.body().byteStream(), "$.items[*].metadata");
                        for (Map<String, String> map : raw) {
                            projects.add(new Project(map.get("name"), map.get("uid")));
                        }
                        return projects;
                    } catch (KubernetesClientException e) {
                        LOGGER.error("Error retrieving project list", e);
                        throw new ElasticsearchSecurityException(e.getMessage());
                    } catch (IOException e) {
                        LOGGER.error("Error retrieving project list", e);
                        throw new ElasticsearchException(e);
                    }
                }
            });
        } catch (ExecutionException e) {
            throw new ElasticsearchException(e);
        }
    }
    
    /**
     * Execute a LocalSubectAccessReview
     * 
     * @param token             a token to check
     * @param project           the namespace to check against
     * @param verb              the verb (e.g. view)
     * @param resource          the resource (e.g. pods/log)
     * @param resourceAPIGroup  the group of the resource being checked
     * @param scopes            the scopes:
     *                            null  - use token scopes
     *                            empty - remove scopes
     *                            list  - an array of scopes
     *                            
     * @return  true if the SAR is satisfied
     */
    public boolean localSubjectAccessReview(final String token, 
            final String project, final String verb, final String resource, final String resourceAPIGroup, final String [] scopes) {
        String key = buildKey(token,project,verb,resource,resourceAPIGroup,scopes);
        try {
            return userSARCache.get(key, new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    try (DefaultOpenShiftClient client = factory.buildClient(token)) {
                        XContentBuilder payload = XContentFactory.jsonBuilder()
                                .startObject()
                                .field("kind", "SubjectAccessReview")
                                .field("apiVersion", "authorization.openshift.io/v1")
                                .field("verb", verb)
                                .array("scopes", scopes);
                        if (resource.startsWith("/")) {
                            payload.field("isNonResourceURL", Boolean.TRUE)
                                    .field("path", resource);
                        } else {
                            payload.field("resourceAPIGroup", resourceAPIGroup)
                                    .field("resource", resource)
                                    .field("namespace", project);
                        }
                        payload.endObject();
                        Request request = new Request.Builder()
                                .url(String.format("%sapis/authorization.openshift.io/v1/subjectaccessreviews", client.getMasterUrl(), project))
                                .header(CONTENT_TYPE, APPLICATION_JSON)
                                .header(ACCEPT, APPLICATION_JSON)
                                .post(RequestBody.create(MediaType.parse(APPLICATION_JSON), payload.string()))
                                .build();
                        log(request);
                        Response response = client.getHttpClient().newCall(request).execute();
                        final String body = IOUtils.toString(response.body().byteStream());
                        log(response, body);
                        if (response.code() != RestStatus.CREATED.getStatus()) {
                            throw new ElasticsearchSecurityException("Unable to determine user's operations role", RestStatus.fromCode(response.code()));
                        }
                        return JsonPath.read(body, "$.allowed");
                    } catch (IOException e) {
                        LOGGER.error("Error determining user's role", e);
                    }
                    return false;
                }
            });
        } catch (ExecutionException e) {
            throw new ElasticsearchException(e);
        }
    }

    private String buildKey(final String token,
                            final String project, final String verb, final String resource, final String resourceAPIGroup, final String [] scopes) {
        StringBuilder builder = new StringBuilder();

        if (StringUtils.isNotEmpty(token)) {
            builder.append(token);
        }

        if (StringUtils.isNotEmpty(project)) {
            builder.append(project);
        }

        if (StringUtils.isNotEmpty(verb)) {
            builder.append(verb);
        }

        if (StringUtils.isNotEmpty(resource)) {
            builder.append(resource);
        }

        if (StringUtils.isNotEmpty(resourceAPIGroup)) {
            builder.append(resourceAPIGroup);
        }

        if (scopes != null) {
            builder.append(Joiner.on(",").join(scopes));
        }

        return builder.toString();
    }

    private void log(Request request) {
        if(!LOGGER.isDebugEnabled()) {
            return;
        }
        try {
            LOGGER.debug("Request: {}", request);
            if(request.body() != null) {
                Buffer sink = new Buffer();
                request.body().writeTo(sink);
                LOGGER.debug("Request body: {}", new String(sink.readByteArray()));
            }
        }catch(Exception e) {
            LOGGER.error("Error trying to dump response", e);
        }
    }

    private void log(Response response, String body) {
        if(!LOGGER.isDebugEnabled()) {
            return;
        }
        try {
            LOGGER.debug("Response: {}", response);
            LOGGER.debug("Response body: {}", body);
        }catch(Exception e) {
            LOGGER.error("Error trying to dump response", e);
        }
    }
    
    interface OpenShiftClientFactory {
        default DefaultOpenShiftClient buildClient(final String token) {
            Config config = new ConfigBuilder().withOauthToken(token).build();
            return new DefaultOpenShiftClient(config);
        }
        
    }
}
