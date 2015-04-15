package hudson.plugins.fairscheduler;

import hudson.util.Graph;

/**
 * this will cache the sla chart
 *
 * @author lmaung
 */
public class SLAGraphSingleton {
    private static SLAGraphSingleton instance;

    private Graph graph;

    private SLAGraphSingleton() {
    }

    public static SLAGraphSingleton getInstance() {
        if (instance == null) {
            instance = new SLAGraphSingleton();
        }
        return instance;
    }

    public void setGraph(Graph g) {
        graph = g;
    }

    public Graph getGraph() {
        return graph;
    }
}
