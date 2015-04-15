package hudson.plugins.fairscheduler;

import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue.Executable;
import hudson.model.Queue.Task;
import hudson.model.labels.LabelAtom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * utilities for querying the properties of nodes.
 * 
 * CHECK FOR NULL BEFORE USING ANY REFERENCES! ANY NPE IN HERE WILL CAUSE
 * THE NODE TO BE MARKED AS "DEAD" BY HUDSON!
 *
 * @author lmaung
 */
public class NodeUtil {
    private static final Logger LOGGER = Logger.getLogger(NodeUtil.class.getName());

    /**
     * get node usage histogram of the specific project. returns a map that
     * maps node to counts of builds on that node.
     * 
     * the usage map excludes nodes that currently can not consume the job, even
     * if the nodes had done so in the past. this is needed to prevent hudson
     * from waiting for the ideal least frequently node (which may no longer be
     * configured to consume the job)
     *
     * if a node used in the past has been removed or renamed, then it cannot
     * be tracked because build.getBuiltOn() returns null in these 2 cases.
     *
     * @param project
     * @return map
     */
    protected static Map<Node, Integer> getNodeUsage(AbstractProject project) {
        Map<Node, Integer> frequency = new HashMap<Node, Integer>();
        // add all the nodes for the label assigned to this project
        for (Node possibleNode : project.getAssignedLabel().getNodes()) {
            frequency.put(possibleNode, 0);
        }
        for (Object b : project.getBuilds()) {
            AbstractBuild build = (AbstractBuild) b;
            Node node = build.getBuiltOn(); // null if node no longer exists
            if (node != null) {
                if (node.canTake(project) == null) { // canTake() returns null if node can take the project
                    if (!frequency.containsKey(node)) {
                        frequency.put(node, 0);
                    }
                    int count = frequency.get(node);
                    frequency.put(node, ++count);
                } else {
                    // node can not take the project at this time
                    LOGGER.fine("node " + node.getDisplayName() + " used in build history can't take project "
                            + project.getDisplayName() + " at this time");
                }
            }
        }
        StringBuilder logMessage = new StringBuilder("node usage map for project " + project.getDisplayName() + ":");
        for (Node node: frequency.keySet()) {            
            logMessage.append(" ").append(node.getDisplayName()).append("=>").append(frequency.get(node));
        }
        LOGGER.fine(logMessage.toString());
        return frequency;
    }

    /**
     * returns the sum of the number of builds on all nodes in the map
     *
     * @param map
     * @return
     */
    protected static int getTotalNodeUsage(Map<Node, Integer> map) {
        int sum = 0;
        for (Integer buildCount : map.values()) {
            sum += buildCount;
        }
        return sum;
    }

    /**
     * find the least frequently used node in the given node usage histogram
     *
     * @param frequency
     * @return node
     */
    protected static Node findbyLeastUsed(Map<Node, Integer> frequency) {
        Node node = null;
        int minimumCount = Integer.MAX_VALUE;
        for (Node n : frequency.keySet()) {
            Computer c = n.toComputer();
            if (c != null && c.isOnline()) {
                List<Executor> execs = c.getExecutors();
                for (Executor e : execs) {
                    if (e.isIdle()) {
                        int count = frequency.get(n);
                        if (count <= minimumCount) {
                            node = n;
                            minimumCount = count;
                        }
                    }
                }
            }
        }
        return node;
    }

    /**
     * find all nodes that match the label of the slave node pool assigned to
     * the given project
     *
     * @param task
     * @return list of nodes
     */
    protected static List<Node> findAllIdleLabledNodes(Task task) {
        Label taskLabel = task.getAssignedLabel(); // may return null
        List<Node> idleLabledNodes = new ArrayList<Node>();
        if (taskLabel != null) {
            List<Node> allNodes = Hudson.getInstance().getNodes();
            for (Node n : allNodes) {
                Set<LabelAtom> assignedLabels = n.getAssignedLabels();
                Computer computer = n.toComputer();
                if (assignedLabels != null && !assignedLabels.isEmpty()
                        && assignedLabels.contains(taskLabel)
                        && computer != null
                        && computer.isOnline()
                        && computer.isIdle()
                        && computer.getNumExecutors() > 0) {
                    idleLabledNodes.add(n);
                }
            }
        }
        return idleLabledNodes;
    }
}
