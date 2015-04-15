package hudson.plugins.fairscheduler;

import hudson.Extension;
import hudson.util.Graph;
import hudson.widgets.Widget;
import java.util.logging.Logger;

/**
 * creates a widget in the left-panel. the widget contains a chart.
 *
 * @author lmaung
 */
@Extension
public class LabelSLAWidget<Label> extends Widget {
    private static final Logger LOGGER = Logger.getLogger(LabelSLAWidget.class.getName());

    @Override
    public String getUrlName() {
        return "sla";
    }

    public Graph getGraph() {
        Graph graph = SLAGraphSingleton.getInstance().getGraph();
        return graph;
    }
}
