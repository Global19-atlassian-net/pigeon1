/**
 * File Created at 12-12-31
 *
 * Copyright 2010 dianping.com.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Dianping Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with dianping.com.
 */
package com.dianping.dpsf.invoke;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

import com.dianping.dpsf.DPSFLog;
import com.dianping.dpsf.Initializable;
import com.dianping.dpsf.component.DPSFCallback;
import com.dianping.dpsf.component.DPSFRequest;
import com.dianping.dpsf.component.DPSFResponse;
import com.dianping.dpsf.control.PigeonConfig;
import com.dianping.dpsf.net.channel.Client;
import com.dianping.dpsf.stat.RpcStatsPool;
import com.dianping.dpsf.stat.ServiceStat;
import com.dianping.dpsf.thread.CycThreadPool;
import com.dianping.dpsf.thread.ExeThreadPool;

/**
 * TODO Comment of The Class
 *
 * @author danson.liu
 */
public class RemoteInvocationRepositoryImpl implements RemoteInvocationRepository, Initializable {

    private static Logger                           logger                      = DPSFLog.getLogger();

    private Map<Long, RemoteInvocationBean>         invocations                 = new ConcurrentHashMap<Long, RemoteInvocationBean>();

    private ServiceStat                             clientServiceStat           = ServiceStat.getClientServiceStat();

//    private ExeThreadPool                           callbackExecutor            = new ExeThreadPool("DPSF-Callback-Executor");

    private Runnable                                invocationTimeoutCheck      = new InvocationTimeoutCheck();

    public void put(long sequence, RemoteInvocationBean invocation) {
        invocations.put(sequence, invocation);
    }

    public void remove(long sequence) {
        invocations.remove(sequence);
    }

    public void receiveResponse(DPSFResponse response) {
        RemoteInvocationBean invocationBean = invocations.get(response.getSequence());
        if (invocationBean != null) {
            DPSFRequest request = invocationBean.request;
            try {
                DPSFCallback callback = invocationBean.callback;
                if (callback != null) {
                    Client client = callback.getClient();
                    if (client != null) {
                        RpcStatsPool.flowOut(request, client.getAddress());
                    }
                    callback.callback(response);
                    callback.run();
//                    callbackExecutor.execute(callback);
                }
                clientServiceStat.timeService(request.getServiceName(), request.getCreateMillisTime());
            } finally {
                invocations.remove(response.getSequence());
            }
        } else {
            if (PigeonConfig.isUseNewInvokeLogic()) {
                logger.warn("no request for response:" + response.getSequence());
            }
        }
    }

    public void setClientServiceStat(ServiceStat clientServiceStat) {
        this.clientServiceStat = clientServiceStat;
    }

    public void setInvocationTimeoutCheck(Runnable invocationTimeoutCheck) {
        this.invocationTimeoutCheck = invocationTimeoutCheck;
    }

    public void init() {
        CycThreadPool.getPool().execute(invocationTimeoutCheck);
    }

    private class InvocationTimeoutCheck implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    long currentTime = System.currentTimeMillis();
                    for (Long sequence : invocations.keySet()) {
                        RemoteInvocationBean invocationBean = invocations.get(sequence);
                        if (invocationBean != null) {
                            DPSFRequest request = invocationBean.request;
                            if (request.getCreateMillisTime() + request.getTimeout() < currentTime) {
                                DPSFCallback callback = invocationBean.callback;
                                if (callback != null && callback.getClient() != null) {
                                    RpcStatsPool.flowOut(request, callback.getClient().getAddress());
                                }
                                invocations.remove(sequence);
                                logger.warn("Remove timeout remote call: " + sequence);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Check remote call timeout failed.", e);
                }
            }
        }
    }

}


