/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.handler.admin.api;

import org.apache.solr.api.Command;
import org.apache.solr.api.EndPoint;
import org.apache.solr.api.PayloadObj;
import org.apache.solr.client.solrj.request.beans.DeleteReplicaPropertyPayload;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.handler.admin.CollectionsHandler;

import java.util.HashMap;
import java.util.Map;

import static org.apache.solr.client.solrj.SolrRequest.METHOD.POST;
import static org.apache.solr.common.params.CollectionAdminParams.COLLECTION;
import static org.apache.solr.common.params.CommonParams.ACTION;
import static org.apache.solr.handler.ClusterAPI.wrapParams;
import static org.apache.solr.security.PermissionNameProvider.Name.COLL_EDIT_PERM;

/**
 * V2 API for removing a property from a collection replica
 *
 * This API (POST /v2/collections/collectionName {'delete-replica-property': {...}}) is analogous to the v1
 * /admin/collections?action=DELETEREPLICAPROP command.
 *
 * @see DeleteReplicaPropertyPayload
 */
@EndPoint(
        path = {"/c/{collection}", "/collections/{collection}"},
        method = POST,
        permission = COLL_EDIT_PERM)
public class DeleteReplicaPropertyAPI {
  private static final String V2_DELETE_REPLICA_PROPERTY_CMD = "delete-replica-property";

  private final CollectionsHandler collectionsHandler;

  public DeleteReplicaPropertyAPI(CollectionsHandler collectionsHandler) {
    this.collectionsHandler = collectionsHandler;
  }

  @Command(name = V2_DELETE_REPLICA_PROPERTY_CMD)
  public void deleteReplicaProperty(PayloadObj<DeleteReplicaPropertyPayload> obj) throws Exception {
    final DeleteReplicaPropertyPayload v2Body = obj.get();
    final Map<String, Object> v1Params = v2Body.toMap(new HashMap<>());
    v1Params.put(ACTION, CollectionParams.CollectionAction.DELETEREPLICAPROP.toLower());
    v1Params.put(COLLECTION, obj.getRequest().getPathTemplateValues().get(COLLECTION));

    collectionsHandler.handleRequestBody(wrapParams(obj.getRequest(), v1Params), obj.getResponse());
  }
}
