package hudson.plugins.fairscheduler;

import hudson.Extension;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Queue.BuildableItem;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.util.Graph;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.data.time.Day;
import org.jfree.data.time.Second;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

/**
 * This plugin monitors the queue wait times. To set the threshold, add a global
 * property:
 * - poolmonitor.${poolname}.sla : time in minutes.
 *
 * When a queue wait time for the named pool exceeds the SLA, then hudson will
 * log a ERROR level log message
 * 
 * @author lmaung
 */
@Extension
public class PoolSLAMonitor extends PeriodicWork {

    /**
     * sampling interval
     */
    private final long SAMPLE_INTERVAL = 30 * MIN /*15000*/;
    private final long TIME_TO_KEEP_SAMPLES = 7 * DAY /*5 * MIN*/ ;
    private final int SLA_ENTRIES_TO_KEEP = (int) (TIME_TO_KEEP_SAMPLES / SAMPLE_INTERVAL);
    private static final double MILLISECONDS_IN_MINUTE = 60000.0;
    private static final Logger LOGGER = Logger.getLogger(PoolSLAMonitor.class.getName());
    private static final String POOLMONITOR_PREFIX = "poolmonitor.";
    private static final String POOLMONITOR_POSTFIX = ".sla";
    /**
     * maps label (pool name) to its own bounded queue containing the SLA entries.
     * each SLA entry contains the total wait time of all the waiting jobs
     * in the queue at that point in time.
     * 
     */
    private final HashMap<Label, Queue<PoolSLAEntry>> waitTimeLog = new HashMap<Label, Queue<PoolSLAEntry>>();

    /**
     * this should always return a constant interval period. do not make this dynamic.
     * 
     * @return
     */
    @Override
    public long getRecurrencePeriod() {
        return SAMPLE_INTERVAL;
    }

    /**
     * checks if the label is a real label. hudson assigns a default label to
     * all nodes and we don't want to process nodes that only has this default
     * label as it is not a true label that the use has created
     *
     * @param label
     * @return
     */
    private boolean isTrueLabel(Label label) {
        // if node name is *not* the same as the label name. then process it.
        // this is needed because hudson assigns the node name as the default label name to all nodes
        // and we don't want to count nodes that don't have a real label
        Node[] nodes = new Node[label.getNodes().size()];
        label.getNodes().toArray(nodes);
        return !(nodes.length == 1 && nodes[0].getDisplayName().equals(label.getName()));
    }

    @Override
    protected void doRun() throws Exception {
        Hudson hudson = Hudson.getInstance();
        // instantiate data-structure for each label if missing
        for (Label label : hudson.getLabels()) {
            if (!waitTimeLog.containsKey(label)) {
                if (isTrueLabel(label)) {
                    waitTimeLog.put(label, new LinkedBlockingQueue<PoolSLAEntry>(SLA_ENTRIES_TO_KEEP));
                }
            }
        }

        // this will contain sum of wait times for this snapshot for each label
        HashMap<Label, PoolSLAEntry> snapshot = new HashMap<Label, PoolSLAEntry>();

        // builds that are waiting in the queue
        List<BuildableItem> buildsInWaiting = hudson.getQueue().getBuildableItems();

        // populate the snapshot wait times 
        for (BuildableItem waitingBuild : buildsInWaiting) {
            Label label = waitingBuild.task.getAssignedLabel();
            if (isTrueLabel(label)) {
                long now = System.currentTimeMillis();
                long waitTime = now - waitingBuild.buildableStartMilliseconds;
                if (!snapshot.containsKey(label)) {
                    snapshot.put(label, new PoolSLAEntry(now, 0, 0));
                }
                snapshot.get(label).incrementTotalWaitTime(waitTime).incrementBuildCount();
            }
        }

        LOGGER.log(Level.INFO, "current snapshot: {0}", snapshot); // keep this here so we can always look at the logs for debugging things way back in time

        // add to the bounded-queue. use hudson's master list of the labels as key
        for (Label label : hudson.getLabels()) {
            if (waitTimeLog.containsKey(label)) {
                Queue q = waitTimeLog.get(label);
                if (q.size() >= SLA_ENTRIES_TO_KEEP) {
                    q.poll();
                }
                if (snapshot.containsKey(label)) {
                    q.offer(snapshot.get(label)); // use sample data
                } else {
                    q.offer(new PoolSLAEntry(System.currentTimeMillis(), 0, 0)); // no sample data
                }
            }
        }


        EnvironmentVariablesNodeProperty globalConfigMap = hudson.getGlobalNodeProperties().get(EnvironmentVariablesNodeProperty.class);
        // inspect the snapshot. use snapshot's list of the labels as key
        for (Label label : snapshot.keySet()) {
            if (globalConfigMap != null) {
                String labelName = label.getDisplayName();
                String poolSLAPropertyName = POOLMONITOR_PREFIX + labelName + POOLMONITOR_POSTFIX;
                String configuredSLA = globalConfigMap.getEnvVars().get(poolSLAPropertyName); // minutes
                if (configuredSLA != null && Character.isDigit(configuredSLA.trim().charAt(0))) {
                    long sla = Long.parseLong(configuredSLA);
                    PoolSLAEntry entry = snapshot.get(label);
                    long averageWait = entry.getTotalWaitTime() / entry.getTotalWaitingBuilds();
                    if (averageWait > sla * MILLISECONDS_IN_MINUTE) {
                        LOGGER.log(Level.SEVERE, "queue wait time ({0} minutes) for pool ''{1}'' exceeded SLA ({2} minutes)",
                                new Object[]{averageWait / MILLISECONDS_IN_MINUTE, labelName, sla});
                    }
                }
            }
        }

        buildGraphInMemory();
    }

    /**
     * only doRun() should call this. "graph" instance is updated every sampling
     * interval.
     * 
     */
    private void buildGraphInMemory() {
        Graph graph = new Graph(Calendar.getInstance(), 336, 240) {

            @Override
            protected JFreeChart createGraph() {
                TimeSeriesCollection dataset = new TimeSeriesCollection();
                for (Label label : waitTimeLog.keySet()) {
                    Queue<PoolSLAEntry> q = waitTimeLog.get(label);
                    PoolSLAEntry[] entries = new PoolSLAEntry[q.size()];
                    q.toArray(entries);
                    TimeSeries series = new TimeSeries(label, Day.class);
                    for (PoolSLAEntry entry : entries) {
                        double averageWait = entry.getTotalWaitingBuilds() > 0
                                ? entry.getTotalWaitTime() / entry.getTotalWaitingBuilds() / MILLISECONDS_IN_MINUTE : 0D;
                        series.addOrUpdate(new Second(new Date(entry.getTimeStamp())), averageWait);
                    }
                    dataset.addSeries(series);
                }

                JFreeChart chart = ChartFactory.createTimeSeriesChart(null /* don't want title inside the chart area*/,
                        null, "minutes", dataset, true, false, false);
                return chart;
            }
        };
        SLAGraphSingleton.getInstance().setGraph(graph); // update the singleton/cache
    }

    /**
     * one sample that contains the sum of all the waiting times and the
     * count of all the jobs at the sampling time
     *
     */
    private class PoolSLAEntry {

        private long timeStamp;
        private long totalWaitTime; // in milliseconds
        private int totalWaitingBuilds;

        private PoolSLAEntry(long timeStamp, long totalWaitTime, int totalWaitingBuilds) {
            this.timeStamp = timeStamp;
            this.totalWaitTime = totalWaitTime;
            this.totalWaitingBuilds = totalWaitingBuilds;
        }

        public long getTimeStamp() {
            return timeStamp;
        }

        public long getTotalWaitTime() {
            return totalWaitTime;
        }

        public int getTotalWaitingBuilds() {
            return totalWaitingBuilds;
        }

        public PoolSLAEntry incrementTotalWaitTime(long additionalWaitTime) {
            totalWaitTime += additionalWaitTime;
            return this;
        }

        public PoolSLAEntry incrementBuildCount() {
            totalWaitingBuilds++;
            return this;
        }

        @Override
        public String toString() {
            return totalWaitTime + " ms - " + totalWaitingBuilds + " builds";
        }
    }
}
