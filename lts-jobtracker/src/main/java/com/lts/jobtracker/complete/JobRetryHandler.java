package com.lts.jobtracker.complete;

import com.lts.core.commons.utils.CollectionUtils;
import com.lts.core.domain.JobMeta;
import com.lts.core.domain.JobRunResult;
import com.lts.core.json.JSON;
import com.lts.core.logger.Logger;
import com.lts.core.logger.LoggerFactory;
import com.lts.core.support.CronExpressionUtils;
import com.lts.core.support.JobUtils;
import com.lts.core.support.SystemClock;
import com.lts.jobtracker.domain.JobTrackerAppContext;
import com.lts.queue.domain.JobPo;
import com.lts.store.jdbc.exception.DupEntryException;

import java.util.Date;
import java.util.List;

/**
 * @author Robert HG (254963746@qq.com) on 11/11/15.
 */
public class JobRetryHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobRetryHandler.class);

    private JobTrackerAppContext appContext;
    private long retryInterval = 30 * 1000;     // 默认30s

    public JobRetryHandler(JobTrackerAppContext appContext) {
        this.appContext = appContext;
        this.retryInterval = appContext.getConfig().getParameter("jobtracker.job.retry.interval.millis", 30 * 1000);
    }

    public void onComplete(List<JobRunResult> results) {

        if (CollectionUtils.isEmpty(results)) {
            return;
        }
        for (JobRunResult result : results) {

            JobMeta jobMeta = result.getJobMeta();
            // 1. 加入到重试队列
            JobPo jobPo = appContext.getExecutingJobQueue().getJob(jobMeta.getJobId());
            if (jobPo == null) {    // 表示已经被删除了
                continue;
            }

            // 重试次数+1
            jobPo.setRetryTimes((jobPo.getRetryTimes() == null ? 0 : jobPo.getRetryTimes()) + 1);
            // 1 分钟重试一次吧
            Long nextRetryTriggerTime = SystemClock.now() + retryInterval;

            if (jobPo.isCron()) {
                // 如果是 cron Job, 判断任务下一次执行时间和重试时间的比较
                JobPo cronJobPo = appContext.getCronJobQueue().getJob(jobMeta.getJobId());
                if (cronJobPo != null) {
                    Date nextTriggerTime = CronExpressionUtils.getNextTriggerTime(cronJobPo.getCronExpression());
                    if (nextTriggerTime != null && nextTriggerTime.getTime() < nextRetryTriggerTime) {
                        // 表示下次还要执行, 并且下次执行时间比下次重试时间要早, 那么不重试，直接使用下次的执行时间
                        nextRetryTriggerTime = nextTriggerTime.getTime();
                        jobPo = cronJobPo;
                    } else {
                        jobPo.setInternalExtParam("isRetry", "true");
                    }
                }
            } else if (jobPo.isRepeatable()) {
                JobPo repeatJobPo = appContext.getRepeatJobQueue().getJob(jobMeta.getJobId());
                if (repeatJobPo != null) {
                    // 比较下一次重复时间和重试时间
                    if (repeatJobPo.getRepeatCount() == -1 || (repeatJobPo.getRepeatedCount() < repeatJobPo.getRepeatCount())) {
                        long nexTriggerTime = JobUtils.getRepeatNextTriggerTime(jobPo);
                        if (nexTriggerTime < nextRetryTriggerTime) {
                            // 表示下次还要执行, 并且下次执行时间比下次重试时间要早, 那么不重试，直接使用下次的执行时间
                            nextRetryTriggerTime = nexTriggerTime;
                            jobPo = repeatJobPo;
                        } else {
                            jobPo.setInternalExtParam("isRetry", "true");
                        }
                    }
                }
            } else {
                jobPo.setInternalExtParam("isRetry", "true");
            }

            // 加入到队列, 重试
            jobPo.setTaskTrackerIdentity(null);
            jobPo.setIsRunning(false);
            jobPo.setGmtModified(SystemClock.now());
            // 延迟重试时间就等于重试次数(分钟)
            jobPo.setTriggerTime(nextRetryTriggerTime);
            try {
                appContext.getExecutableJobQueue().add(jobPo);
            } catch (DupEntryException e) {
                LOGGER.warn("ExecutableJobQueue already exist:" + JSON.toJSONString(jobPo));
            }
            // 从正在执行的队列中移除
            appContext.getExecutingJobQueue().remove(jobPo.getJobId());
        }
    }
}
