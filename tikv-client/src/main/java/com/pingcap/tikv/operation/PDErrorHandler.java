/*
 *
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.pingcap.tikv.operation;

import com.pingcap.tikv.PDClient;
import com.pingcap.tikv.exception.GrpcException;
import com.pingcap.tikv.kvproto.Pdpb;
import com.pingcap.tikv.util.BackOff;
import com.pingcap.tikv.util.BackoffFunction;

import java.util.function.Function;

public class PDErrorHandler<RespT> implements ErrorHandler<RespT> {
  private final Function<RespT, Pdpb.Error> getError;
  private final PDClient client;

  public PDErrorHandler(Function<RespT, Pdpb.Error> errorExtractor, PDClient client) {
    this.getError = errorExtractor;
    this.client = client;
  }

  @Override
  public boolean handleResponseError(BackOff backOff, RespT resp) {
    // TODO: Check pd error TiDB impl
    if (resp == null) {
      return false;
    }
    Pdpb.Error error = getError.apply(resp);
    if (error != null) {
      client.updateLeader();
      backOff.doBackOff(BackoffFunction.BackOffFuncType.boPDRPC,
          new RuntimeException(error.getMessage()));
      return true;
    }
    return false;
  }

  @Override
  public boolean handleRequestError(BackOff backOff, Exception e) {
    backOff.doBackOff(BackoffFunction.BackOffFuncType.boPDRPC, e);
    return true;
  }
}
