package hudson.plugins.fairscheduler;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue.Task;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * This class tries to assign builds to nodes in a fair manner (best-effort).
 * Best-effort means that if the heuristics fail to find a good match,
 * the default behavior will be executed.
 * 
 * However, this results a load balancing effect that is the opposite of default
 * behavior in most cases (see notes on canTake()).
 *
 * This is targeted for folks with a large pool of nodes that need to be shared
 * in a fair way.
 *
 * If you are not using any pools (using labels), this plugin does not change
 * the effective load balancer behavior.
 *
 * @author lmaung
 */
@Extension
public class FairQueueTaskDispatcher extends QueueTaskDispatcher {

    private static final Logger LOGGER = Logger.getLogger(FairQueueTaskDispatcher.class.getName());

    /**
     * goes through a bunch of heuristics to determine if the specific node can
     * be run on the specific task. the logic is:
     *
     * 1) if test node has label, and job has no label, follow job configuration
     * 2) if test node no label, and job has a label, reject it
     * 3) if test node is the only one left available, allow it
     * 4a) in valid usage map, if the least used node is not found, fallback to "test node".canTake()
     * 4b) in valid usage map, if the least used node == test node, allow it
     * 5) if usage map is empty/null and test node was used for the last build, reject it
     * 6) otherwise, allow it
     *
     * this is a callback which is invoked by a trigger call in LoadBalancer
     * (at some point).
     *
     * @param node
     * @param task
     * @return
     */
    @Override
    public CauseOfBlockage canTake(Node node, Task task) {
        Hudson instance = Hudson.getInstance();
        String taskName = task.getFullDisplayName();
        String nodeName = node.getDisplayName();
        if (task.getAssignedLabel() == null) {
            // if no label on this node. 

            // use default behavior. task without label can run on any node as configured
            // return node.canTake(task); // this is the plain vanilla behavior

            // augmented behavior. task without label can run only on node without label
            Set<LabelAtom> nodeLabels = node.getAssignedLabels();
            if (nodeLabels.size() == 1) {
                // node has no label other than its name. obey configuration
                CauseOfBlockage decision = node.canTake(task);
                LOGGER.fine("for task " + taskName + ": node " + nodeName
                        + " has no label other than its name. obey configuration. decision=" + decision);
                return decision;
            } else {
                // task has no label. node *has* label. don't allow running on node
                LOGGER.fine("for task " + taskName 
                        + ": task has no label. node *has* label. don't allow running on node: "
                        + nodeName);
                return new CauseOfBlockage.BecauseNodeIsBusy(node);
            }
        } else {
            Map<Node, Integer> usageMap = null;
            if (task instanceof AbstractProject) {
                usageMap = NodeUtil.getNodeUsage((AbstractProject) task); 
                //NOTE: getNodeUsage() internally calls canTake(), so it is not necessary
                //      to call canTake() again on a non-null node in this set
            }
            List<Node> idleLabledNodes = NodeUtil.findAllIdleLabledNodes(task);
            if (idleLabledNodes.size() == 1 && idleLabledNodes.get(0) == node) {
                // overriding decision - if only 1 node available, just take it regardless of other heuristics
                LOGGER.fine("for task " + taskName + ": overriding decision - if only 1 node "
                        + nodeName + "available, just take it regardless of other heuristics");
                return null;
            } else if (usageMap != null && !usageMap.isEmpty() && NodeUtil.getTotalNodeUsage(usageMap) > 0) {
                // if usage map has some data based on past builds,
                // find the least used node
                Node bestMatchNode = NodeUtil.findbyLeastUsed(usageMap);
                if (node == bestMatchNode) {
                    // test node *is* the least used node. allow.
                    LOGGER.fine("for task " + taskName + ": test node "
                            + nodeName + " *is* the least used node. ALLOW.");
                    return null;
                } else {
                    if (bestMatchNode == null) {
                        // there's no "best match node" (ie, can't find it by inspecting build history)
                        CauseOfBlockage decision = node.canTake(task);
                        LOGGER.fine("for task " + taskName 
                                + ": bestmatchnode is null! fallback to node.canTake(). decision=" + decision);
                        return decision;
                    }  else {
                        // test node is *not* the least used node. reject.
                        LOGGER.fine("for task " + taskName + ": test node " + nodeName 
                                + " *is not* the least used node " + bestMatchNode.getDisplayName() + ". reject.");
                        return new CauseOfBlockage.BecauseNodeIsBusy(node);
                    }
                }
            } else if (node == task.getLastBuiltOn()) { //if "least used node" could not be found, then:
                // reject this node because the project was last run on this node
                LOGGER.fine("for task " + taskName + ": reject this node because the project was last run here: " + nodeName);
                return new CauseOfBlockage.BecauseNodeIsBusy(node);
            } else {
                // project was not last run on this node. proceed to take this node
                LOGGER.fine("for task " + taskName + ": project was not last run on this node. proceed to take this node: " + nodeName);
                return null;
            }
        }
    }
}
