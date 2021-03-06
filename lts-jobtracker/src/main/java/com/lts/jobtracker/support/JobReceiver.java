package com.lts.jobtracker.support;

import com.lts.biz.logger.domain.JobLogPo;
import com.lts.biz.logger.domain.LogType;
import com.lts.core.commons.utils.Assert;
import com.lts.core.commons.utils.CollectionUtils;
import com.lts.core.commons.utils.StringUtils;
import com.lts.core.constant.Level;
import com.lts.core.domain.Job;
import com.lts.core.exception.JobReceiveException;
import com.lts.core.logger.Logger;
import com.lts.core.logger.LoggerFactory;
import com.lts.core.protocol.command.JobSubmitRequest;
import com.lts.core.spi.ServiceLoader;
import com.lts.core.support.CronExpressionUtils;
import com.lts.core.support.JobDomainConverter;
import com.lts.core.support.SystemClock;
import com.lts.jobtracker.domain.JobTrackerAppContext;
import com.lts.jobtracker.id.IdGenerator;
import com.lts.jobtracker.monitor.JobTrackerMStatReporter;
import com.lts.queue.domain.JobPo;
import com.lts.store.jdbc.exception.DupEntryException;

import java.util.Date;
import java.util.List;

/**
 * @author Robert HG (254963746@qq.com) on 8/1/14.
 *         任务处理器
 */
public class JobReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobReceiver.class);

    private JobTrackerAppContext appContext;
    private IdGenerator idGenerator;
    private JobTrackerMStatReporter stat;

    public JobReceiver(JobTrackerAppContext appContext) {
        this.appContext = appContext;
        this.stat = (JobTrackerMStatReporter) appContext.getMStatReporter();
        this.idGenerator = ServiceLoader.load(IdGenerator.class, appContext.getConfig());
    }

    /**
     * jobTracker 接受任务
     */
    public void receive(JobSubmitRequest request) throws JobReceiveException {

        List<Job> jobs = request.getJobs();
        if (CollectionUtils.isEmpty(jobs)) {
            return;
        }
        JobReceiveException exception = null;
        for (Job job : jobs) {
            try {
                addToQueue(job, request);
            } catch (Exception t) {
                if (exception == null) {
                    exception = new JobReceiveException(t);
                }
                exception.addJob(job);
            }
        }

        if (exception != null) {
            throw exception;
        }
    }

    private JobPo addToQueue(Job job, JobSubmitRequest request) {

        JobPo jobPo = null;
        boolean success = false;
        BizLogCode code = null;
        try {
            jobPo = JobDomainConverter.convert(job);
            if (jobPo == null) {
                LOGGER.warn("Job can not be null。{}", job);
                return null;
            }
            if (StringUtils.isEmpty(jobPo.getSubmitNodeGroup())) {
                jobPo.setSubmitNodeGroup(request.getNodeGroup());
            }
            // 设置 jobId
            jobPo.setJobId(idGenerator.generate(jobPo));

            // 添加任务
            addJob(job, jobPo);

            success = true;
            code = BizLogCode.SUCCESS;

        } catch (DupEntryException e) {
            // 已经存在
            if (job.isReplaceOnExist()) {
                Assert.notNull(jobPo);
                success = replaceOnExist(job, jobPo);
                code = success ? BizLogCode.DUP_REPLACE : BizLogCode.DUP_FAILED;
            } else {
                code = BizLogCode.DUP_IGNORE;
                LOGGER.info("Job already exist. nodeGroup={}, {}", request.getNodeGroup(), job);
            }
        } finally {
            if (success) {
                stat.incReceiveJobNum();
            }
        }

        // 记录日志
        jobBizLog(jobPo, code);

        return jobPo;
    }

    /**
     * 添加任务
     */
    private void addJob(Job job, JobPo jobPo) throws DupEntryException {
        if (job.isCron()) {
            addCronJob(jobPo);
        } else if (job.isRepeatable()) {
            addRepeatJob(jobPo);
        } else {
            boolean needAdd2ExecutableJobQueue = true;
            String ignoreAddOnExecuting = CollectionUtils.getValue(jobPo.getInternalExtParams(), "__LTS_ignoreAddOnExecuting");
            if (ignoreAddOnExecuting != null && "true".equals(ignoreAddOnExecuting)) {
                if (appContext.getExecutingJobQueue().getJob(jobPo.getTaskTrackerNodeGroup(), jobPo.getTaskId()) != null) {
                    needAdd2ExecutableJobQueue = false;
                }
            }
            if (needAdd2ExecutableJobQueue) {
                appContext.getExecutableJobQueue().add(jobPo);
            }
        }
        LOGGER.info("Receive Job success. {}", job);
    }

    /**
     * 更新任务
     **/
    private boolean replaceOnExist(Job job, JobPo jobPo) {

        // 得到老的jobId
        JobPo oldJobPo;
        if (job.isCron()) {
            oldJobPo = appContext.getCronJobQueue().getJob(job.getTaskTrackerNodeGroup(), job.getTaskId());
        } else if (job.isRepeatable()) {
            oldJobPo = appContext.getRepeatJobQueue().getJob(job.getTaskTrackerNodeGroup(), job.getTaskId());
        } else {
            oldJobPo = appContext.getExecutableJobQueue().getJob(job.getTaskTrackerNodeGroup(), job.getTaskId());
        }
        if (oldJobPo != null) {
            String jobId = oldJobPo.getJobId();
            // 1. 删除任务
            appContext.getExecutableJobQueue().remove(job.getTaskTrackerNodeGroup(), jobId);
            if (job.isCron()) {
                appContext.getCronJobQueue().remove(jobId);
            } else if (job.isRepeatable()) {
                appContext.getRepeatJobQueue().remove(jobId);
            }
            jobPo.setJobId(jobId);
        }

        // 2. 重新添加任务
        try {
            addJob(job, jobPo);
        } catch (DupEntryException e) {
            // 一般不会走到这里
            LOGGER.warn("Job already exist twice. {}", job);
            return false;
        }
        return true;
    }

    /**
     * 添加Cron 任务
     */
    private void addCronJob(JobPo jobPo) throws DupEntryException {
        Date nextTriggerTime = CronExpressionUtils.getNextTriggerTime(jobPo.getCronExpression());
        if (nextTriggerTime != null) {
            // 1.add to cron job queue
            appContext.getCronJobQueue().add(jobPo);

            // 没有正在执行, 则添加
            if (appContext.getExecutingJobQueue().getJob(jobPo.getTaskTrackerNodeGroup(), jobPo.getTaskId()) == null) {
                // 2. add to executable queue
                jobPo.setTriggerTime(nextTriggerTime.getTime());
                appContext.getExecutableJobQueue().add(jobPo);
            }
        }
    }

    /**
     * 添加Repeat 任务
     */
    private void addRepeatJob(JobPo jobPo) throws DupEntryException {
        // 1.add to repeat job queue
        appContext.getRepeatJobQueue().add(jobPo);

        // 没有正在执行, 则添加
        if (appContext.getExecutingJobQueue().getJob(jobPo.getTaskTrackerNodeGroup(), jobPo.getTaskId()) == null) {
            // 2. add to executable queue
            appContext.getExecutableJobQueue().add(jobPo);
        }
    }

    /**
     * 记录任务日志
     */
    private void jobBizLog(JobPo jobPo, BizLogCode code) {
        if (jobPo == null) {
            return;
        }

        try {
            // 记录日志
            JobLogPo jobLogPo = JobDomainConverter.convertJobLog(jobPo);
            jobLogPo.setSuccess(true);
            jobLogPo.setLogType(LogType.RECEIVE);
            jobLogPo.setLogTime(SystemClock.now());

            switch (code) {
                case SUCCESS:
                    jobLogPo.setLevel(Level.INFO);
                    jobLogPo.setMsg("Receive Success");
                    break;
                case DUP_IGNORE:
                    jobLogPo.setLevel(Level.WARN);
                    jobLogPo.setMsg("Already Exist And Ignored");
                    break;
                case DUP_FAILED:
                    jobLogPo.setLevel(Level.ERROR);
                    jobLogPo.setMsg("Already Exist And Update Failed");
                    break;
                case DUP_REPLACE:
                    jobLogPo.setLevel(Level.INFO);
                    jobLogPo.setMsg("Already Exist And Update Success");
                    break;
            }

            appContext.getJobLogger().log(jobLogPo);
        } catch (Throwable t) {     // 日志记录失败不影响正常运行
            LOGGER.error("Receive Job Log error ", t);
        }
    }

    private enum BizLogCode {
        DUP_IGNORE,     // 添加重复并忽略
        DUP_REPLACE,    // 添加时重复并覆盖更新
        DUP_FAILED,     // 添加时重复再次添加失败
        SUCCESS,     // 添加成功
    }

}
