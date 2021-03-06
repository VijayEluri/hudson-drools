/**
 * 
 */
package hudson.drools.renderer;

import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import jenkins.model.Jenkins;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;

public class Build extends WorkItem {

	private final ItemGroup context;
	public String project;
	Run run;

	public Build(ItemGroup context, String type, String name, String id, String project,
				 int x, int y, int width, int height) {
		super(type, name, id, x, y, width, height);
		this.context = context;
		this.project = project;
	}

	@Override
	public Image getImage() {
		return RendererConstants.workItemImage;
	}

	@Override
	public boolean paintIcon(Graphics2D g2, int x, int y) {
		if (run == null || run.getResult() == null) {
			return super.paintIcon(g2, x, y);
		}
		Color c;
		if (run.getResult() == Result.SUCCESS) {
			c = RendererConstants.BUILD_SUCCESS_COLOR;
		} else if (run.getResult() == Result.UNSTABLE) {
			c = RendererConstants.BUILD_UNSTABLE_COLOR;
		} else if (run.getResult() == Result.ABORTED) {
			c = RendererConstants.BUILD_CANCELED_COLOR;
		} else {
			c = RendererConstants.BUILD_FAILED_COLOR;
		}
		g2.translate(x, y);
		GraphicsUtil.paintBall(g2, c);
		g2.translate(-x, -y);

		return true;
	}

	@Override
	public String getUrl() {
		if (run != null) {
			return run.getUrl();
		}
		
		if (project != null) {
			Job job = Jenkins.getActiveInstance().getItem(project, context, Job.class);
			if (job != null) {
				return job.getUrl();
			}
		}
		
		return null;
	}
	
}