package com.twitter.ambrose.hive;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.TaskResult;
import org.apache.hadoop.hive.ql.exec.TaskRunner;
import org.apache.hadoop.hive.ql.hooks.ExecuteWithHookContext;
import org.apache.hadoop.hive.ql.hooks.HookContext;

import com.twitter.ambrose.model.DAGNode;
import com.twitter.ambrose.model.Event;
import com.twitter.ambrose.model.Job;
import com.twitter.ambrose.model.hadoop.MapReduceJobState;

/**
 * Hook invoked when a job fails.
 * Updates job event to 'FAILED' and waits for <tt>ambrose.post.script.sleep.seconds</tt> seconds
 * before exiting.
 * <br>
 * Called by the main thread
 * 
 * @author Lorand Bendig <lbendig@gmail.com>
 *
 */
public class AmbroseHiveFailHook implements ExecuteWithHookContext {
    
    private static final Log LOG = LogFactory.getLog(AmbroseHiveFailHook.class);
    private static final String POST_SCRIPT_SLEEP_SECS_PARAM = "ambrose.post.script.sleep.seconds";

    @Override
    public void run(HookContext hookContext) throws Exception {

        HiveConf conf = hookContext.getConf();
        Properties allConfProps = conf.getAllProperties();
        String queryId = AmbroseHiveUtil.getHiveQueryId(conf);

        HiveProgressReporter reporter = HiveProgressReporter.get();

        List<TaskRunner> completeTaskList = hookContext.getCompleteTaskList();
        Field _taskResultField = accessTaskResultField();
        for (TaskRunner taskRunner : completeTaskList) {
            TaskResult taskResult = (TaskResult) _taskResultField.get(taskRunner);
            //get non-running, failed jobs
            if (!taskResult.isRunning() && taskResult.getExitVal() != 0) {
                Task<? extends Serializable> task = taskRunner.getTask();
                String nodeId = AmbroseHiveUtil.getNodeIdFromNodeName(conf, task.getId());
                DAGNode<Job> dagNode = reporter.getDAGNodeFromNodeId(nodeId);
                HiveJob job = (HiveJob) dagNode.getJob();
                job.setConfiguration(allConfProps);
                MapReduceJobState mrJobState = getJobState(job);
                mrJobState.setSuccessful(false);
                reporter.addJob((Job) job);
                reporter.pushEvent(queryId, new Event.JobFailedEvent(dagNode));
            }
        }
        
        reporter.restoreEventStack();
        String sleepTime = System.getProperty(POST_SCRIPT_SLEEP_SECS_PARAM, "10");
        try {
            int sleepTimeSeconds = Integer.parseInt(sleepTime);

            LOG.info("Script failed but sleeping for " + sleepTimeSeconds
                    + " seconds to keep the HiveStats REST server running. Hit ctrl-c to exit.");
            
            Thread.sleep(sleepTimeSeconds * 1000L);
            reporter.stopServer();

        }
        catch (NumberFormatException e) {
            LOG.warn(POST_SCRIPT_SLEEP_SECS_PARAM + " param is not a valid number, not sleeping: "
                    + sleepTime);
        }
        catch (InterruptedException e) {
            LOG.warn("Sleep interrupted", e);
        }
    }
    
    /**
     * Accessess the TaskResult of the completed task
     * @return
     */
    private Field accessTaskResultField() {
        Field field = null;
        try {
            field = TaskRunner.class.getDeclaredField("result");
            field.setAccessible(true);
        }
        catch (Exception e) {
            LOG.fatal("Can't access to TaskResult at " + TaskRunner.class.getName() + "!");
            throw new RuntimeException("Incompatible Hive API found!", e);
        }
        return field;
    }
    
    private MapReduceJobState getJobState(HiveJob job) {
        MapReduceJobState jobState = job.getMapReduceJobState();
        if (jobState != null) {
            return jobState;
        }
        //if job fails immediately right after its submission 
        jobState = new MapReduceJobState();
        jobState.setJobId(job.getId());
        return jobState;
    }

}
