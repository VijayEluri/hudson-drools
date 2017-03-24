package hudson.drools;

import com.gargoylesoftware.htmlunit.html.HtmlButton;

import org.junit.Assert;
import org.jvnet.hudson.test.FailureBuilder;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.SubmittableElement;

import hudson.model.FreeStyleProject;
import hudson.model.Result;

public class CancelBuildTest extends DroolsTestCase {

	public void testCancel() throws Exception {
		DroolsProject wf = createProject("SimpleProject",
				"SimpleProjectTest-1.rf");

		FreeStyleProject project1 = hudson.createProject(
				FreeStyleProject.class, "Project1");
		
		// to make sure the workflow does not complete
		project1.getBuildersList().add(new FailureBuilder());

		wf.scheduleBuild(0);

		assertBuildResult(wf, Result.SUCCESS, 1);
		
		Assert.assertTrue(wf.getLastBuild().isRunning());
		
		HtmlPage page = new WebClient().goTo(wf.getLastBuild().getUrl() + "/cancel");
		HtmlForm form = page.getFirstByXPath("//form[@action='doCancel']");
		((HtmlButton) form.getFirstByXPath("//button")).click();
		
		Assert.assertTrue(wf.getLastBuild().isAborted());
		
		

	}
}
