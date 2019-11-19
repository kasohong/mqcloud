package com.sohu.tv.mq.cloud.task;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import com.sohu.tv.mq.cloud.bo.Cluster;
import com.sohu.tv.mq.cloud.bo.TopicTraffic;
import com.sohu.tv.mq.cloud.service.ClusterService;
import com.sohu.tv.mq.cloud.service.ConsumerTrafficService;
import com.sohu.tv.mq.cloud.service.TopicService;
import com.sohu.tv.mq.cloud.service.TopicTrafficService;
import com.sohu.tv.mq.cloud.service.TrafficService;
import com.sohu.tv.mq.cloud.util.DateUtil;
import com.sohu.tv.mq.cloud.util.Result;

import net.javacrumbs.shedlock.core.SchedulerLock;

/**
 * topic流量任务
 * 
 * @Description:
 * @author yongfeigao
 * @date 2018年6月26日
 */
public class TrafficTask {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private static final int ONE_MIN = 1 * 60 * 1000;

    @Autowired
    private TopicTrafficService topicTrafficService;

    @Autowired
    private ConsumerTrafficService consumerTrafficService;

    @Autowired
    private TaskExecutor taskExecutor;

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private TopicService topicService;

    /**
     * topic流量收集
     */
    @Scheduled(cron = "20 */1 * * * *")
    @SchedulerLock(name = "collectTopicTraffic", lockAtMostFor = ONE_MIN, lockAtLeastFor = 59000)
    public void collectTopicTraffic() {
        taskExecutor.execute(new Runnable() {
            public void run() {
                if (clusterService.getAllMQCluster() == null) {
                    logger.warn("collectTopicTraffic mqcluster is null");
                    return;
                }
                for (Cluster mqCluster : clusterService.getAllMQCluster()) {
                    long start = System.currentTimeMillis();
                    int size = topicTrafficService.collectTraffic(mqCluster);
                    logger.info("fetch cluster:{} topic traffic, size:{}, use:{}ms", mqCluster, size,
                            System.currentTimeMillis() - start);
                }
            }
        });
    }

    /**
     * 消费者流量收集
     */
    @Scheduled(cron = "30 */1 * * * *")
    @SchedulerLock(name = "collectConsumerTraffic", lockAtMostFor = ONE_MIN, lockAtLeastFor = 59000)
    public void collectConsumerTraffic() {
        taskExecutor.execute(new Runnable() {
            public void run() {
                if (clusterService.getAllMQCluster() == null) {
                    logger.warn("collectConsumerTraffic mqcluster is null");
                    return;
                }
                for (Cluster mqCluster : clusterService.getAllMQCluster()) {
                    long start = System.currentTimeMillis();
                    int size = consumerTrafficService.collectTraffic(mqCluster);
                    logger.info("fetch cluster:{} consumer traffic, size:{}, use:{}ms", mqCluster, size,
                            System.currentTimeMillis() - start);
                }
            }
        });
    }

    /**
     * 聚合topic 10分钟流量
     */
    @Scheduled(cron = "38 */5 * * * *")
    @SchedulerLock(name = "aggregateTopicTraffic", lockAtMostFor = ONE_MIN, lockAtLeastFor = 59000)
    public void collectTopicHourTraffic() {
        taskExecutor.execute(new Runnable() {
            public void run() {
                Cluster[] clusters = clusterService.getAllMQCluster();
                if(clusters == null) {
                    return;
                }
                // 获取线上集群
                List<Integer> onlineClusterList = new ArrayList<Integer>();
                for(Cluster cluster : clusters) {
                    if(cluster.online()) {
                        onlineClusterList.add(cluster.getId());
                    }
                }
                if(onlineClusterList.size() == 0) {
                    return;
                }
                logger.info("aggregate topic traffic start");
                Date now = new Date();
                // 计算10分钟间隔
                List<String> timeList = new ArrayList<String>();
                Date begin = new Date(now.getTime() - 10 * ONE_MIN + 30);
                while (begin.before(now)) {
                    String time = DateUtil.getFormat(DateUtil.HHMM).format(begin);
                    timeList.add(time);
                    begin.setTime(begin.getTime() + ONE_MIN);
                }

                int size = 0;
                int update = 0;
                Result<List<TopicTraffic>> result = topicTrafficService.query(DateUtil.formatYMD(now), timeList, onlineClusterList);
                if (result.isNotEmpty()) {
                    List<TopicTraffic> topicTrafficList = result.getResult();
                    size = topicTrafficList.size();
                    Result<Integer> rst = topicService.updateCount(topicTrafficList);
                    if (rst.isOK()) {
                        update = rst.getResult();
                    }
                }
                logger.info("aggregate topic traffic, size:{}, update:{} use:{}ms", size, update,
                        System.currentTimeMillis() - now.getTime());
            }
        });
    }

    /**
     * 删除统计表数据
     */
    @Scheduled(cron = "0 0 4 * * ?")
    @SchedulerLock(name = "deleteTraffic", lockAtMostFor = 10 * ONE_MIN, lockAtLeastFor = 59000)
    public void deleteTraffic() {
        delete(topicTrafficService);
        delete(consumerTrafficService);
    }

    /**
     * 删除数据
     * 
     * @param trafficService
     */
    private void delete(TrafficService<?> trafficService) {
        // 30天以前
        long now = System.currentTimeMillis();
        Date thirtyDaysAgo = new Date(now - 30L * 24 * 60 * 60 * 1000);
        logger.info("{}, delete date:{}", trafficService.getClass().getSimpleName(), thirtyDaysAgo);
        Result<Integer> result = trafficService.delete(thirtyDaysAgo);
        if (result.isOK()) {
            logger.info("{}, delete success, rows:{} use:{}ms", trafficService.getClass().getSimpleName(),
                    result.getResult(), (System.currentTimeMillis() - now));
        } else {
            if (result.getException() != null) {
                logger.error("{}, delete err", trafficService.getClass().getSimpleName(), result.getException());
            } else {
                logger.info("{}, delete failed", trafficService.getClass().getSimpleName());
            }
        }
    }
    
    /**
     * 重置2天前的流量为0
     */
    @Scheduled(cron = "0 0 6 * * ?")
    @SchedulerLock(name = "resetTopicTraffic", lockAtMostFor = 10 * ONE_MIN, lockAtLeastFor = 59000)
    public void resetTopicTraffic() {
        long start = System.currentTimeMillis();
        Result<?> rst = topicService.resetCount(2);
        logger.info("resetTopicTraffic rst:{} use:{}", rst, System.currentTimeMillis() - start);
    }
}
