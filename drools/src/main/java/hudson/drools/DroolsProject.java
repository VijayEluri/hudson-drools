package hudson.drools;

import hudson.Extension;
import hudson.drools.renderer.RuleFlowRenderer;
import hudson.model.*;
import hudson.model.Descriptor.FormException;
import hudson.model.RunMap.Constructor;
import hudson.model.Cause.UserCause;
import hudson.model.Queue.Executable;
import hudson.model.Queue.WaitingItem;
import hudson.model.queue.CauseOfBlockage;
import hudson.scheduler.CronTabList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.Future;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.xml.xpath.XPathExpressionException;

import hudson.security.ACL;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.acegisecurity.Authentication;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.dom4j.DocumentException;
import org.drools.logger.KnowledgeRuntimeLoggerFactory;
import org.drools.runtime.process.ProcessInstance;
import org.drools.runtime.process.WorkItemManager;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;

import antlr.ANTLRException;

public class DroolsProject extends Job<DroolsProject, DroolsRun> implements
		TopLevelItem, hudson.model.Queue.FlyweightTask, BuildableItem {

	private boolean disabled;
	private File archive;
	private String workflowId;
	private String triggerSpec;
	private List<WorkItemAction> pendingBuilds = new ArrayList<WorkItemAction>();

	private transient DroolsSession session;
	private transient CronTabList tabs;
	private transient String processXML;
	private transient ClassLoader workflowCL;
	private transient SoftReference<RuleFlowRenderer> renderer;

	/**
	 * All the builds keyed by their build number.
	 */
	protected transient/* almost final */RunMap<DroolsRun> builds = new RunMap<>();

	protected DroolsProject(ItemGroup<?> parent, String name) {
		super(parent, name);
	}

	@Override
	protected SortedMap<Integer, ? extends DroolsRun> _getRuns() {
		return builds.getView();
	}

	@Override
	public boolean isBuildable() {
		return true;
	}

	@Override
	protected void removeRun(DroolsRun run) {
		this.builds.remove(run);
	}

	@Override
	public void onLoad(ItemGroup<? extends Item> parent, String name) throws IOException {
		super.onLoad(parent, name);

		this.builds = new RunMap<>(getBuildDir(), new Constructor() {
			@Override
			public Run create(File dir) throws IOException {
				DroolsRun newBuild = new DroolsRun(DroolsProject.this, dir);
				builds.put(newBuild.getNumber(), newBuild);
				return newBuild;
			}
		});

		if (workflowId != null) {
			try {
				set(triggerSpec, archive, workflowId);
			} catch (Exception e) {
				disabled = true;
				e.printStackTrace();
			}
		}

	}

	private int getMaxProcessInstanceId() {
		int max = 0;
		for (DroolsRun build : builds.values()) {
			max = Math.max(max, (int) build.getProcessInstanceId());
		}
		return max;
	}

	public void testSetWorkflow(String workflowId) throws IOException {
		set(null, null, workflowId);
	}

	void set(String triggerSpec, File archive, String workflowId) throws IOException {
		ClassLoader workflowCL = archive != null ?
				ArchiveManager.getInstance().getClassLoader(archive) :
				Thread.currentThread().getContextClassLoader();

		InputStream resource = workflowCL.getResourceAsStream(workflowId);

		if (resource == null) {
			throw new IOException("workflow ID unknown");
		}

		String processXML = hudson.util.IOUtils.toString(resource);

		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(PluginImpl.class.getClassLoader());

		try {
			int initialId = getMaxProcessInstanceId() + 1;
			DroolsSession session = new DroolsSession(new File(getRootDir(), "session.ser"), processXML, initialId);

			CronTabList tabs = null;
			if (!StringUtils.isEmpty(triggerSpec)) {
				try {
					tabs = CronTabList.create(triggerSpec);
				} catch (ANTLRException e) {
					e.printStackTrace();
				}
			} else {
				tabs = null;
				triggerSpec = null;
			}

			// all is well -- let's commit
			this.archive = archive;
			this.session = session;
			this.triggerSpec = triggerSpec;
			this.tabs = tabs;
			this.processXML = processXML;
			this.workflowCL = workflowCL;
			this.workflowId = workflowId;
			this.renderer = null;

			WorkItemManager workItemManager = session.getSession()
					.getWorkItemManager();
			workItemManager.registerWorkItemHandler("Build",
					new BuildWorkItemHandler(this));
			workItemManager.registerWorkItemHandler("Human Task",
					new HumanTaskHandler(this));
			workItemManager.registerWorkItemHandler("Script",
					new ScriptHandler(this));
			workItemManager.registerWorkItemHandler("E-Mail",
					new EmailWorkItemHandler());

			KnowledgeRuntimeLoggerFactory
					.newConsoleLogger(session.getSession());
			new WorkingMemoryHudsonLogger(session.getSession(), this);

		} finally {
			Thread.currentThread().setContextClassLoader(cl);
		}
	}

	@Extension
	public static final class DescriptorImpl extends TopLevelItemDescriptor {

		@Override
		public String getDisplayName() {
			return "Drools Project";
		}

		@Override
		public DroolsProject newInstance(String name) {
			return new DroolsProject(Hudson.getInstance(), name);
		}

		@Override
		public hudson.model.TopLevelItem newInstance(hudson.model.ItemGroup itemGroup, String name) {
			return new hudson.drools.DroolsProject(itemGroup, name);
		}

	}

	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) Hudson.getInstance().getDescriptor(DroolsProject.class);
	}

	@Override
	public synchronized void doConfigSubmit(StaplerRequest req,
			StaplerResponse rsp) throws IOException, ServletException,
			FormException {
		checkPermission(CONFIGURE);

		JSONObject form = req.getSubmittedForm();
		String workflowId = form.getString("workflowId");
		String triggerSpec = form.getString("triggerSpec");

		FileItem fileItem = req.getFileItem("archive");
		boolean fileItemUploaded = fileItem != null && fileItem.getSize() > 0;
		File newArchive = fileItemUploaded ?  ArchiveManager.getInstance().uploadFile(fileItem) : archive;

		set(triggerSpec, newArchive, workflowId);

		super.doConfigSubmit(req, rsp);
	}

	/**
	 * Schedules a new build command.
	 */
	public HttpResponse doBuild() throws IOException, ServletException {
		checkPermission(BUILD);

		Cause cause = new UserCause();

		scheduleBuild(cause);

		return new ForwardToPreviousPage();
	}

	public boolean scheduleBuild(Cause cause, Action... actions) {
		if (isDisabled())
			return false;

		List<Action> queueActions = new ArrayList(Arrays.asList(actions));
		if (cause != null) {
			queueActions.add(new CauseAction(cause));
		}

		return Hudson.getInstance().getQueue().add(this, 0,
				queueActions.toArray(new Action[queueActions.size()]));
	}

	public boolean isDisabled() {
		return session == null || disabled;
	}

	public void setDisabled(boolean disable) {
		this.disabled = disable;
	}

	public HttpResponse doEnable() throws IOException {
		checkPermission(CONFIGURE);
		disabled = false;
		save();
		return new ForwardToPreviousPage();
	}
	public HttpResponse doDisable() throws IOException {
		checkPermission(CONFIGURE);
		disabled = true;
		save();
		return new ForwardToPreviousPage();
	}

	public void checkAbortPermission() {
		checkPermission(AbstractProject.ABORT);
	}

	public Executable createExecutable() throws IOException {
		DroolsRun run = new DroolsRun(this);
		builds.put(run);
		return run;
	}

	public Label getAssignedLabel() {
		return null;
	}

	public long getEstimatedDuration() {
		return -1;
	}

	public Node getLastBuiltOn() {
		return null;
	}

	public String getWhyBlocked() {
		return null;
	}

	public boolean hasAbortPermission() {
		return false;
	}

	public boolean isBuildBlocked() {
		return false;
	}

	public ResourceList getResourceList() {
		return new ResourceList();
	}

	public String getProcessId() {
		return session.getProcessId();
	}

	public synchronized RuleFlowRenderer getRuleFlowRenderer() {
		if (renderer == null || renderer.get() == null) {
			renderer = new SoftReference<>(new RuleFlowRenderer(getParent(), processXML));
		}
		return renderer.get();
	}

	public void doProcessImage(StaplerRequest req, StaplerResponse rsp)
			throws IOException, XPathExpressionException, DocumentException {
		ServletOutputStream output = rsp.getOutputStream();
		rsp.setContentType("image/png");
		getRuleFlowRenderer().write(output);
		output.flush();
		output.close();
	}

	public void doProcessImageSVG(StaplerRequest req, StaplerResponse rsp)
			throws IOException, XPathExpressionException, DocumentException {
		ServletOutputStream output = rsp.getOutputStream();
		rsp.setContentType("image/svg+xml");
		getRuleFlowRenderer().writeSVG(output);
		output.flush();
		output.close();
	}

	@Exported
	public String getProcessXML() {
		return processXML;
	}

	@Exported
	public String getTriggerSpec() {
		return triggerSpec;
	}

	public boolean scheduleBuild() {
		return scheduleBuild(null, new Action[0]);
	}

	public boolean scheduleBuild(Cause c) {
		return scheduleBuild(c, new Action[0]);
	}

	public boolean scheduleBuild(int quietPeriod) {
		return scheduleBuild(null, new Action[0]);
	}

	public boolean scheduleBuild(int quietPeriod, Cause c) {
		return scheduleBuild(null, new CauseAction(c));
	}

    public Future<DroolsRun> scheduleBuild2() {
        if (!isBuildable())
            return null;

        WaitingItem i = Hudson.getInstance().getQueue().schedule(this, 0, new Action[0]);
        if(i!=null)
            return (Future)i.getFuture();
        return null;
    }

	public CronTabList getTabs() {
		return tabs;
	}

	public void doSubmitWorkflow(StaplerRequest request, StaplerResponse rsp)
			throws IOException {
		checkPermission(CONFIGURE);

		if (!"POST".equals(request.getMethod())) {
			rsp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
					"POST expected");
			return;
		}

		String processXML = IOUtils.toString(request.getInputStream());
		set(triggerSpec, null, null); // TODO

		save();
	}

	@Override
	protected void performDelete() throws IOException, InterruptedException {
		if (session != null)
			session.dispose();
		super.performDelete();
	}

	/*
	 * We need two strategies two find the DroolsRun. When the process is
	 * starting, the DroolsRun does not know its processInstanceId yet, so we
	 * query the process variable "run".
	 *
	 * After the process is completed, the processInstance or variable will be
	 * gone, so we need to iterate over all the builds to find the right one.
	 */
	public DroolsRun getFromProcessInstance(long processInstanceId) {
		DroolsRun result = null;
		ProcessInstance processInstance = session.getSession()
				.getProcessInstance(processInstanceId);
		if (processInstance != null) {
			result = DroolsRun.getFromProcessInstance(processInstance);
		}
		if (result == null) {
			// probably because the workflow has been completed
			for (DroolsRun run : getBuilds()) {
				if (run.getProcessInstanceId() == processInstanceId) {
					return run;
				}
			}
		}
		return result;
	}

	public <T> T run(SessionCallable<T> callable) throws Exception {
		return session.run(callable);
	}

	public void dispose() {
		if (session != null) session.dispose();
		for (DroolsRun run : getBuilds()) {
			run.dispose();
		}
	}

	public Class<Script> getScript(String scriptName) {
		try {
			Class<?> cl = workflowCL.loadClass(scriptName);
			if (Script.class.isAssignableFrom(cl)) {
				return (Class<Script>) cl;
			} else {
				throw new IllegalArgumentException("class " + scriptName + " does not extend hudson.drools.Script");
			}
		} catch (ClassNotFoundException e) {
			throw new IllegalArgumentException("unknown class " + scriptName);
		}
	}

	public DroolsSession getSession() {
		return session;
	}

	public synchronized void addPendingWorkItemBuild(WorkItemAction action)
			throws IOException {
		pendingBuilds.add(action);
		save();
	}

	public synchronized void removePendingWorkItemBuild(WorkItemAction action)
			throws IOException {
		pendingBuilds.remove(action);
		save();
	}

	public List<WorkItemAction> getPendingBuilds() {
		return pendingBuilds;
	}

	public HttpResponse doRescheduleWorkItemBuild(@QueryParameter(required=true) String workItemId)
			throws IOException {
        long id = Long.parseLong(workItemId);
		for (WorkItemAction action : pendingBuilds) {
			if (action.getWorkItemId() == id) {
				action.scheduleBuild();
				return new ForwardToPreviousPage();
			}
		}
		throw new IOException("Unknown work item id: " + workItemId);
	}

	public Object readResolve() {
		if (pendingBuilds == null)
			pendingBuilds = new ArrayList<WorkItemAction>();
		
		if (archive != null) {
			File home = Jenkins.getInstance().getRootDir();
			if (!archive.toPath().startsWith(home.toPath())) {
				String p = archive.getAbsolutePath();
				p = p.substring(1 + p.indexOf(File.separatorChar + "drools" + File.separatorChar));
				archive = new File(home, p);
			}
		}
		
		return this;
	}

	public CauseOfBlockage getCauseOfBlockage() {
		return null;
	}

	public boolean isConcurrentBuild() {
		return false;
	}

	public java.util.Collection<? extends hudson.model.queue.SubTask> getSubTasks() {
		return java.util.Collections.singleton(this);
	}

	@Nonnull
	public Authentication getDefaultAuthentication() {
		return ACL.SYSTEM;
	}

	@Nonnull
	public Authentication getDefaultAuthentication(Queue.Item item) {
		return getDefaultAuthentication();
	}

	public hudson.model.Queue.Task getOwnerTask() {
		return this;
	}

	public Object getSameNodeConstraint() {
		return null;
	}

	public String getWorkflowId() {
		return workflowId;
	}

	public String getArchiveInfo() throws IOException {
		return archive != null ? ArchiveManager.getInstance().getInfo(archive) : null;
	}



}
