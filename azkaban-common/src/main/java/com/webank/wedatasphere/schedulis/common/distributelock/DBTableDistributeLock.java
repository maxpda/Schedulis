/*
 * Copyright 2020 WeBank
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.wedatasphere.schedulis.common.distributelock;

import com.webank.wedatasphere.schedulis.common.utils.HttpUtils;
import org.apache.log4j.Logger;

import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

import azkaban.db.DatabaseOperator;

/**
 * @author georgeqiao
 * @Title: DBTableDistributeLock
 * @date 2019/11/1220:49
 * @Description: DB 实现的分布式锁
 */
@Singleton
public class DBTableDistributeLock extends AbstractDistributeLock{

    private static final Logger log = Logger.getLogger(DBTableDistributeLock.class);

    @Inject
    public DBTableDistributeLock(DatabaseOperator dbOperator) {
        super(dbOperator);
    }

    /**
     * 获取锁
     * todo 锁失效问题  为锁异步续命 /可以做一个定时任务，每隔一定时间把数据库中的超时数据清理一遍。
     * @param lock_resource     需要锁住的资源
     * @param locktimeout(毫秒) 持有锁的有效时间，防止死锁
     * @param gettimeout(毫秒)  获取锁的超时时间，这个时间内获取不到将重试
     * @return
     */
    @Override
    public synchronized boolean lock(String lock_resource, long locktimeout, long gettimeout) {
        boolean lockResult = false;
        try {
            String current_request_Id = getRequestId();
            long getLockEndTime = System.currentTimeMillis() + gettimeout;
            while (System.currentTimeMillis() < getLockEndTime) {
                log.debug("start to get exist lock, lock_resource is {} " + lock_resource);
                DistributeLock distributeLock = get(lock_resource);
                if (Objects.isNull(distributeLock)) {
                    distributeLock = new DistributeLock(current_request_Id,lock_resource,1,1, HttpUtils.getLinuxLocalIp(log),
                            System.currentTimeMillis() + locktimeout,System.currentTimeMillis(),System.currentTimeMillis());
                    log.debug("there is no exist lock ,the system will request a new lock {} new distributeLock info: " + distributeLock.toString());
                    insert(distributeLock);
                    lockResult = true;
                    return lockResult;
                } else {
                    log.debug("The lock already exists {} exist distributeLock info: " + distributeLock.toString());
                    String lockRequest_id = distributeLock.getRequest_id();
                    if ("".equals(lockRequest_id)) {
                        distributeLock.setRequest_id(current_request_Id);
                        distributeLock.setLock_count(1);
                        distributeLock.setTimeout(System.currentTimeMillis() + locktimeout);
                        log.debug("The lock is not owner by anyone , assign it to the current thread {} distributeLock info: " + distributeLock.toString());
                        if (updateLock(distributeLock) == 1) {
                            lockResult = true;
                            return lockResult;
                        }
                    } else if (current_request_Id.equals(lockRequest_id)) {
                        //如果 current_request_Id 和表中 lockRequest_id 一样表示锁被当前线程持有者，此时需要加重入锁
                        distributeLock.setTimeout(System.currentTimeMillis() + locktimeout);
                        distributeLock.setLock_count(distributeLock.getLock_count() + 1);
                        log.debug("The lock lockRequest_id is equal to current thread request_Id , and then the lock would reaccess {} distributeLock info: " + distributeLock.toString());
                        if (updateLock(distributeLock) == 1) {
                            lockResult = true;
                            return lockResult;
                        }
                    } else {
                        log.debug("The lock_resource is locked by other thread request_Id, {} distributeLock info: " + distributeLock.toString());
                        if(System.currentTimeMillis() > distributeLock.getTimeout()){
                            //lock timeout
                            distributeLock.setRequest_id(current_request_Id);
                            distributeLock.setLock_count(1);
                            distributeLock.setTimeout(System.currentTimeMillis() + locktimeout);
                            if (updateLock(distributeLock) == 1) {
                                lockResult = true;
                                return lockResult;
                            }
                        }else{
                            lockResult = false;
                            return lockResult;
                        }
                    }
                }
            }
            log.warn("get lock timeout");
        }catch (Exception e){
            log.error("get distribute lock failed",e);
        }finally {
            lockResult = false;
        }
        return lockResult;
    }

    @Override
    public synchronized void unlock(String lock_resource) {
        String current_request_Id = getRequestId();
        DistributeLock distributeLock = get(lock_resource);
        //当前线程requestId和库中request_id一致 && lock_count>0，表示可以释放锁
        if (Objects.nonNull(distributeLock) && current_request_Id.equals(distributeLock.getRequest_id()) && distributeLock.getLock_count() > 0) {
            log.debug("The lock will be deleted {} distributeLock info: " + distributeLock.toString());
            delete(distributeLock);
        }
    }

    @Override
    public synchronized int resetLock(DistributeLock distributeLock) {
        distributeLock.setRequest_id("");
        distributeLock.setLock_count(0);
        distributeLock.setTimeout(0L);
        return updateLock(distributeLock);
    }


//    private Date lockExtension(Date date, long extenTime) {
//        if (Objects.isNull(seconds)){
//            seconds = DEFAULT_EXPIRED_SECONDS;
//        }
//        Calendar calendar = Calendar.getInstance();
//        calendar.setTime(date);
//        calendar.add(Calendar.SECOND, seconds);
//        return calendar.getTime();
//    }

}
