/*
 * Copyright 2012 Goodow.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.goodow.realtime.server.rpc;

import com.goodow.realtime.channel.constant.Constants.Params;
import com.goodow.realtime.server.auth.AccountContext;
import com.goodow.realtime.server.model.ObjectId;
import com.goodow.realtime.server.model.Session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.walkaround.slob.server.AccessDeniedException;
import com.google.walkaround.slob.server.SlobFacilities;
import com.google.walkaround.slob.server.SlobNotFoundException;
import com.google.walkaround.slob.server.SlobStore;
import com.google.walkaround.slob.server.SlobStore.ConnectResult;
import com.google.walkaround.util.server.servlet.AbstractHandler;
import com.google.walkaround.util.server.servlet.BadRequestException;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class PollHandler extends AbstractHandler {

  @Inject
  SlobFacilities slobFacilities;
  @Inject
  DeltaHandler deltaHandler;
  @Inject
  Provider<AccountContext> context;

  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    JsonObject payload = new JsonParser().parse(RpcUtil.readRequestBody(req)).getAsJsonObject();
    String sessionId = requireParameter(req, Params.SESSION_ID);
    JsonArray toRtn;
    try {
      toRtn = fetchDeltas(payload.get(Params.IDS).getAsJsonArray(), sessionId);
    } catch (SlobNotFoundException e) {
      throw new BadRequestException("Object not found or access denied", e);
    } catch (AccessDeniedException e) {
      throw new BadRequestException("Object not found or access denied", e);
    } catch (NumberFormatException nfe) {
      throw new BadRequestException("Parse error", nfe);
    }

    RpcUtil.writeJsonResult(req, resp, toRtn.toString());
  }

  private JsonArray fetchDeltas(JsonArray ids, String sessionId) throws IOException,
      SlobNotFoundException, AccessDeniedException {
    JsonArray msgs = new JsonArray();
    SlobStore store = slobFacilities.getSlobStore();
    String token = null;
    for (JsonElement elem : ids) {
      JsonArray array = elem.getAsJsonArray();
      ObjectId key = new ObjectId(array.get(0).getAsString());
      long startRev = array.get(1).getAsLong();
      Long endVersion = array.size() >= 3 ? array.get(2).getAsLong() : null;

      ConnectResult r = null;
      try {
        r =
            store
                .reconnect(key, new Session(context.get().getAccountInfo().getUserId(), sessionId));
      } catch (SlobNotFoundException e) {
        if (startRev == 1) {
          continue;
        }
        throw e;
      }
      if (r.getChannelToken() != null) {
        assert token == null || token.equals(r.getChannelToken());
        token = r.getChannelToken();
      }
      JsonObject msg = new JsonObject();
      msg.addProperty(Params.ID, key.toString());
      boolean isEmpty = deltaHandler.fetchDeltas(msg, key, startRev - 1, endVersion);
      if (!isEmpty) {
        msgs.add(msg);
      }
    }
    if (token != null) {
      JsonObject tokenMsg = new JsonObject();
      tokenMsg.addProperty(Params.ID, Params.TOKEN);
      tokenMsg.addProperty(Params.TOKEN, token);
      msgs.add(tokenMsg);
    }
    return msgs;
  }
}
