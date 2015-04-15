package hudson.plugins.fairscheduler;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.DumbSlave;

import java.io.IOException;
import java.util.HashMap;

import org.junit.Assert;
import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.TestBuilder;

/**
 *
 * @author lmaung
 */
public class FairQueueTestLargePool extends HudsonTestCase {

    /**
     * runs a bunch of tests on a pool, and asserts all nodes are used equally
     *
     * @throws Exception
     */
    public void testFairness10Slaves2BuildsEach() throws Exception {
        // create pool
        Label pool = new LabelAtom("pool");

        // create slaves
        int size = 10;
        DumbSlave[] slave = new DumbSlave[size];
        for (int i = 0; i < size; i++) {
            slave[i] = createSlave(pool);
        }

        // create project
        FreeStyleProject project1 = createFreeStyleProject("project1");
        project1.setAssignedLabel(pool);
        project1.getBuildersList().add(new TestBuilder() {

            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                Thread.sleep(1000);
                return true;
            }
        });

        // kick off builds
        int iterations = 2;
        HashMap<Node, Integer> frequency = new HashMap<Node, Integer>();
        FreeStyleBuild[] build = new FreeStyleBuild[size * iterations];
        for (int i = 0; i < size * iterations; i++) {
            build[i] = project1.scheduleBuild2(0).get();
            Node node = build[i].getBuiltOn();
            if (!frequency.containsKey(node)) {
                frequency.put(node, 0);
            }
            int count = frequency.get(node);
            frequency.put(node, ++count);
        }

        // assert
        for (Integer value : frequency.values()) {
            Assert.assertEquals(iterations, value);
        }
    }
}
