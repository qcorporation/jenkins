/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.model;

import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.Util;
import hudson.maven.MavenModuleSet;
import hudson.scm.NullSCM;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.BatchFile;
import hudson.tasks.BuildTrigger;
import hudson.tasks.Shell;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.OneShotEvent;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.ResourceBundle;
import java.util.Vector;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.TestPluginManager;
import org.jvnet.hudson.test.recipes.PresetData;
import org.jvnet.hudson.test.recipes.PresetData.DataSet;
import org.kohsuke.args4j.CmdLineException;

public class AbstractProjectTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void configRoundtrip() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        Label l = j.jenkins.getLabel("foo && bar");
        project.setAssignedLabel(l);
        j.configRoundtrip(project);

        assertEquals(l, project.getAssignedLabel());
    }

    /**
     * Tests the workspace deletion.
     */
    @Test
    public void wipeWorkspace() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildersList().add(Functions.isWindows() ? new BatchFile("echo hello") : new Shell("echo hello"));

        FreeStyleBuild b = project.scheduleBuild2(0).get();

        assertTrue("Workspace should exist by now", b.getWorkspace().exists());

        project.doDoWipeOutWorkspace();

        assertFalse("Workspace should be gone by now", b.getWorkspace().exists());
    }

    /**
     * Makes sure that the workspace deletion is protected.
     */
    @Test
    @PresetData(DataSet.NO_ANONYMOUS_READACCESS)
    public void wipeWorkspaceProtected() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildersList().add(Functions.isWindows() ? new BatchFile("echo hello") : new Shell("echo hello"));

        FreeStyleBuild b = project.scheduleBuild2(0).get();

        assert b.getWorkspace().exists() : "Workspace should exist by now";

        // make sure that the action link is protected
        JenkinsRule.WebClient wc = j.createWebClient();
        try {
            wc.getPage(new WebRequest(new URL(wc.getContextPath() + project.getUrl() + "doWipeOutWorkspace"), HttpMethod.POST));
            fail("Expected HTTP status code 403");
        } catch (FailingHttpStatusCodeException e) {
            assertEquals(HttpURLConnection.HTTP_FORBIDDEN, e.getStatusCode());
        }
    }

    /**
     * Makes sure that the workspace deletion link is not provided when the user
     * doesn't have an access.
     */
    @Test
    @PresetData(DataSet.ANONYMOUS_READONLY)
    public void wipeWorkspaceProtected2() throws Exception {
        ((GlobalMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()).add(AbstractProject.WORKSPACE, "anonymous");

        // make sure that the deletion is protected in the same way
        wipeWorkspaceProtected();

        // there shouldn't be any "wipe out workspace" link for anonymous user
        JenkinsRule.WebClient webClient = j.createWebClient();
        HtmlPage page = webClient.getPage(j.jenkins.getItem("test0"));

        page = (HtmlPage) page.getAnchorByText("Workspace").click();
        try {
            String wipeOutLabel = ResourceBundle.getBundle("hudson/model/AbstractProject/sidepanel").getString("Wipe Out Workspace");
            page.getAnchorByText(wipeOutLabel);
            fail("shouldn't find a link");
        } catch (ElementNotFoundException e) {
            // OK
        }
    }

    /**
     * Tests the &lt;optionalBlock @field> round trip behavior by using
     * {@link AbstractProject#concurrentBuild}
     */
    @Test
    public void optionalBlockDataBindingRoundtrip() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        for (boolean b : new boolean[] {true, false}) {
            p.setConcurrentBuild(b);
            j.submit(j.createWebClient().getPage(p, "configure").getFormByName("config"));
            assertEquals(b, p.isConcurrentBuild());
        }
    }

    /**
     * Tests round trip configuration of the blockBuildWhenUpstreamBuilding
     * field
     */
    @Test
    @Issue("JENKINS-4423")
    public void configuringBlockBuildWhenUpstreamBuildingRoundtrip() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.setBlockBuildWhenUpstreamBuilding(false);

        HtmlForm form = j.createWebClient().getPage(p, "configure").getFormByName("config");
        HtmlInput input = form.getInputByName("blockBuildWhenUpstreamBuilding");
        assertFalse("blockBuildWhenUpstreamBuilding check box is checked.", input.isChecked());

        input.setChecked(true);
        j.submit(form);
        assertTrue("blockBuildWhenUpstreamBuilding was not updated from configuration form", p.blockBuildWhenUpstreamBuilding());

        form = j.createWebClient().getPage(p, "configure").getFormByName("config");
        input = form.getInputByName("blockBuildWhenUpstreamBuilding");
        assert input.isChecked() : "blockBuildWhenUpstreamBuilding check box is not checked.";
    }

    /**
     * Unless the concurrent build option is enabled, polling and build should
     * be mutually exclusive to avoid allocating unnecessary workspaces.
     */
    @Test
    @Issue("JENKINS-4202")
    public void pollingAndBuildExclusion() throws Exception {
        final OneShotEvent sync = new OneShotEvent();

        final FreeStyleProject p = j.createFreeStyleProject();
        FreeStyleBuild b1 = j.buildAndAssertSuccess(p);

        p.setScm(new NullSCM() {
            @Override
            public boolean pollChanges(AbstractProject project, Launcher launcher, FilePath workspace, TaskListener listener) {
                try {
                    sync.block();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }

            /**
             * Don't write 'this', so that subtypes can be implemented as
             * anonymous class.
             */
            private Object writeReplace() {
                return new Object();
            }

            @Override
            public boolean requiresWorkspaceForPolling() {
                return true;
            }
            @Override
            public SCMDescriptor<?> getDescriptor() {
                return new SCMDescriptor<SCM>(null) {
                };
            }
        });
        Thread t = new Thread() {
            @Override
            public void run() {
                p.pollSCMChanges(StreamTaskListener.fromStdout());
            }
        };
        try {
            t.start();
            Future<FreeStyleBuild> f = p.scheduleBuild2(0);

            // add a bit of delay to make sure that the blockage is happening
            Thread.sleep(3000);

            // release the polling
            sync.signal();

            FreeStyleBuild b2 = j.assertBuildStatusSuccess(f);

            // they should have used the same workspace.
            assertEquals(b1.getWorkspace(), b2.getWorkspace());
        } finally {
            t.interrupt();
        }
    }

    @Test
    @Issue("JENKINS-1986")
    public void buildSymlinks() throws Exception {
        Assume.assumeFalse("If we're on Windows, don't bother doing this", Functions.isWindows());

        FreeStyleProject job = j.createFreeStyleProject();
        job.getBuildersList().add(new Shell("echo \"Build #$BUILD_NUMBER\"\n"));
        FreeStyleBuild build = job.scheduleBuild2(0, new Cause.UserCause()).get();
        File lastSuccessful = new File(job.getRootDir(), "lastSuccessful"),
                lastStable = new File(job.getRootDir(), "lastStable");
        // First build creates links
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
        FreeStyleBuild build2 = job.scheduleBuild2(0, new Cause.UserCause()).get();
        // Another build updates links
        assertSymlinkForBuild(lastSuccessful, 2);
        assertSymlinkForBuild(lastStable, 2);
        // Delete latest build should update links
        build2.delete();
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
        // Delete all builds should remove links
        build.delete();
        assertFalse("lastSuccessful link should be removed", lastSuccessful.exists());
        assertFalse("lastStable link should be removed", lastStable.exists());
    }

    private static void assertSymlinkForBuild(File file, int buildNumber)
            throws IOException, InterruptedException {
        assert file.exists() : "should exist and point to something that exists";
        assert Util.isSymlink(file) : "should be symlink";
        String s = FileUtils.readFileToString(new File(file, "log"));
        assert s.contains("Build #" + buildNumber + "\n") : "link should point to build #$buildNumber, but link was: ${Util.resolveSymlink(file, TaskListener.NULL)}\nand log was:\n$s";
    }

    @Test
    @Issue("JENKINS-2543")
    public void symlinkForPostBuildFailure() throws Exception {
        Assume.assumeFalse("If we're on Windows, don't bother doing this", Functions.isWindows());

        // Links should be updated after post-build actions when final build result is known
        FreeStyleProject job = j.createFreeStyleProject();
        job.getBuildersList().add(new Shell("echo \"Build #$BUILD_NUMBER\"\n"));
        FreeStyleBuild build = job.scheduleBuild2(0, new Cause.UserCause()).get();
        assert Result.SUCCESS == build.getResult();
        File lastSuccessful = new File(job.getRootDir(), "lastSuccessful"),
                lastStable = new File(job.getRootDir(), "lastStable");
        // First build creates links
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
        // Archive artifacts that don't exist to create failure in post-build action
        job.getPublishersList().add(new ArtifactArchiver("*.foo", "", false, false));
        build = job.scheduleBuild2(0, new Cause.UserCause()).get();
        assert Result.FAILURE == build.getResult();
        // Links should not be updated since build failed
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
    }

    /* TODO too slow, seems capable of causing testWorkspaceLock to time out:
    @Test
    @Issue("JENKINS-15156")
    public void testGetBuildAfterGC() {
        FreeStyleProject job = j.createFreeStyleProject();
        job.scheduleBuild2(0, new Cause.UserIdCause()).get();
        j.jenkins.queue.clearLeftItems();
        MemoryAssert.assertGC(new WeakReference(job.getLastBuild()));
        assert job.lastBuild != null;
    }
     */

    @Test
    @Issue("JENKINS-18678")
    public void renameJobLostBuilds() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("initial");
        j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        assertEquals(1, p.getBuilds().size());
        p.renameTo("edited");
        p._getRuns().purgeCache();
        assertEquals(1, p.getBuilds().size());
        MockFolder d = j.jenkins.createProject(MockFolder.class, "d");
        Items.move(p, d);
        assertEquals(p, j.jenkins.getItemByFullName("d/edited"));
        p._getRuns().purgeCache();
        assertEquals(1, p.getBuilds().size());
        d.renameTo("d2");
        p = j.jenkins.getItemByFullName("d2/edited", FreeStyleProject.class);
        p._getRuns().purgeCache();
        assertEquals(1, p.getBuilds().size());
    }

    @Test
    @Issue("JENKINS-17575")
    public void deleteRedirect() throws Exception {
        j.createFreeStyleProject("j1");
        assertEquals("", deleteRedirectTarget("job/j1"));
        j.createFreeStyleProject("j2");
        Jenkins.getInstance().addView(new AllView("v1"));
        assertEquals("view/v1/", deleteRedirectTarget("view/v1/job/j2"));
        MockFolder d = Jenkins.getInstance().createProject(MockFolder.class, "d");
        d.addView(new AllView("v2"));
        for (String n : new String[] {"j3", "j4", "j5"}) {
            d.createProject(FreeStyleProject.class, n);
        }
        assertEquals("job/d/", deleteRedirectTarget("job/d/job/j3"));
        assertEquals("job/d/view/v2/", deleteRedirectTarget("job/d/view/v2/job/j4"));
        assertEquals("view/v1/job/d/", deleteRedirectTarget("view/v1/job/d/job/j5"));
        assertEquals("view/v1/", deleteRedirectTarget("view/v1/job/d")); // JENKINS-23375
    }

    private String deleteRedirectTarget(String job) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        String base = wc.getContextPath();
        String loc = wc.getPage(wc.addCrumb(new WebRequest(new URL(base + job + "/doDelete"), HttpMethod.POST))).getUrl().toString();
        assert loc.startsWith(base) : loc;
        return loc.substring(base.length());
    }

    @Test
    @Issue("JENKINS-18407")
    public void queueSuccessBehavior() throws Exception {
        // prevent any builds to test the behaviour
        j.jenkins.setNumExecutors(0);

        FreeStyleProject p = j.createFreeStyleProject();
        Future<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertNotNull(f);
        Future<FreeStyleBuild> g = p.scheduleBuild2(0);
        assertEquals(f, g);

        p.makeDisabled(true);
        assertNull(p.scheduleBuild2(0));
    }

    /**
     * Do the same as {@link #testQueueSuccessBehavior()} but over HTTP
     */
    @Test
    @Issue("JENKINS-18407")
    public void queueSuccessBehaviorOverHTTP() throws Exception {
        // prevent any builds to test the behaviour
        j.jenkins.setNumExecutors(0);

        FreeStyleProject p = j.createFreeStyleProject();
        JenkinsRule.WebClient wc = j.createWebClient();

        WebResponse rsp = wc.getPage(j.getURL() + p.getUrl() + "build").getWebResponse();
        assertEquals(201, rsp.getStatusCode());
        assertNotNull(rsp.getResponseHeaderValue("Location"));

        WebResponse rsp2 = wc.getPage(j.getURL() + p.getUrl() + "build").getWebResponse();
        assertEquals(201, rsp2.getStatusCode());
        assertEquals(rsp.getResponseHeaderValue("Location"), rsp2.getResponseHeaderValue("Location"));

        p.makeDisabled(true);

        try {
            wc.getPage(j.getURL() + p.getUrl() + "build");
            fail();
        } catch (FailingHttpStatusCodeException e) {
            // request should fail
        }
    }

    /**
     * We used to store {@link AbstractProject#triggers} as {@link Vector}, so
     * make sure we can still read back the configuration from that.
     */
    @Test
    public void vectorTriggers() throws Exception {
        AbstractProject<?, ?> p = (AbstractProject) j.jenkins.createProjectFromXML("foo", getClass().getResourceAsStream("AbstractProjectTest/vectorTriggers.xml"));
        assertEquals(1, p.triggers().size());
        Trigger<?> t = p.triggers().get(0);
        assertEquals(SCMTrigger.class, t.getClass());
        assertEquals("*/10 * * * *", t.getSpec());
    }

    @Test
    @Issue("JENKINS-18813")
    public void removeTrigger() throws Exception {
        AbstractProject<?, ?> p = (AbstractProject) j.jenkins.createProjectFromXML("foo", getClass().getResourceAsStream("AbstractProjectTest/vectorTriggers.xml"));

        TriggerDescriptor SCM_TRIGGER_DESCRIPTOR = (TriggerDescriptor) j.jenkins.getDescriptorOrDie(SCMTrigger.class);
        p.removeTrigger(SCM_TRIGGER_DESCRIPTOR);
        assertEquals(0, p.triggers().size());
    }

    @Test
    @Issue("JENKINS-18813")
    public void addTriggerSameType() throws Exception {
        AbstractProject<?, ?> p = (AbstractProject) j.jenkins.createProjectFromXML("foo", getClass().getResourceAsStream("AbstractProjectTest/vectorTriggers.xml"));

        SCMTrigger newTrigger = new SCMTrigger("H/5 * * * *");
        p.addTrigger(newTrigger);

        assertEquals(1, p.triggers().size());
        Trigger<?> t = p.triggers().get(0);
        assertEquals(SCMTrigger.class, t.getClass());
        assertEquals("H/5 * * * *", t.getSpec());
    }

    @Test
    @Issue("JENKINS-18813")
    public void addTriggerDifferentType() throws Exception {
        AbstractProject<?, ?> p = (AbstractProject) j.jenkins.createProjectFromXML("foo", getClass().getResourceAsStream("AbstractProjectTest/vectorTriggers.xml"));

        TimerTrigger newTrigger = new TimerTrigger("20 * * * *");
        p.addTrigger(newTrigger);

        assertEquals(2, p.triggers().size());
        Trigger<?> t = p.triggers().get(1);
        assertEquals(newTrigger, t);
    }

    /**
     * Trying to POST to config.xml by a different job type should fail.
     */
    @Test
    public void configDotXmlSubmissionToDifferentType() throws Exception {
        TestPluginManager tpm = (TestPluginManager) j.jenkins.pluginManager;
        tpm.installDetachedPlugin("javadoc");
        tpm.installDetachedPlugin("junit");
        tpm.installDetachedPlugin("display-url-api");
        tpm.installDetachedPlugin("mailer");
        tpm.installDetachedPlugin("maven-plugin");

        j.jenkins.setCrumbIssuer(null);
        FreeStyleProject p = j.createFreeStyleProject();

        HttpURLConnection con = postConfigDotXml(p, "<maven2-moduleset />");

        // this should fail with a type mismatch error
        // the error message should report both what was submitted and what was expected
        assertEquals(500, con.getResponseCode());
        String msg = IOUtils.toString(con.getErrorStream());
        System.out.println(msg);
        assertThat(msg, allOf(containsString(FreeStyleProject.class.getName()), containsString(MavenModuleSet.class.getName())));

        // control. this should work
        con = postConfigDotXml(p, "<project />");
        assertEquals(200, con.getResponseCode());
    }

    private HttpURLConnection postConfigDotXml(FreeStyleProject p, String xml) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(j.getURL(), "job/" + p.getName() + "/config.xml").openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/xml");
        con.setDoOutput(true);
        try (OutputStream s = con.getOutputStream()) {
            s.write(xml.getBytes());
        }
        return con;
    }

    @Issue("JENKINS-21017")
    @Test public void doConfigDotXmlReset() throws Exception {
        j.jenkins.setCrumbIssuer(null);
        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(Label.get("whatever"));
        assertEquals("whatever", p.getAssignedLabelString());
        assertThat(p.getConfigFile().asString(), containsString("<assignedNode>whatever</assignedNode>"));
        assertEquals(200, postConfigDotXml(p, "<project/>").getResponseCode());
        assertNull(p.getAssignedLabelString()); // did not work
        assertThat(p.getConfigFile().asString(), not(containsString("<assignedNode>"))); // actually did work anyway
    }

    @Test
    @Issue("JENKINS-27549")
    public void loadingWithNPEOnTriggerStart() throws Exception {
        AbstractProject<?, ?> project = (AbstractProject) j.jenkins.createProjectFromXML("foo", getClass().getResourceAsStream("AbstractProjectTest/npeTrigger.xml"));

        assertEquals(1, project.triggers().size());
    }

    @Test
    @Issue("JENKINS-30742")
    public void resolveForCLI() throws Exception {
        try {
            AbstractProject<?, ?> not_found = AbstractProject.resolveForCLI("never_created");
            fail("Exception should occur before!");
        } catch (CmdLineException e) {
            assertEquals("No such job \u2018never_created\u2019 exists.", e.getMessage());
        }

        AbstractProject<?, ?> project = j.jenkins.createProject(FreeStyleProject.class, "never_created");
        try {
            AbstractProject<?, ?> not_found = AbstractProject.resolveForCLI("never_created1");
            fail("Exception should occur before!");
        } catch (CmdLineException e) {
            assertEquals("No such job \u2018never_created1\u2019 exists. Perhaps you meant \u2018never_created\u2019?", e.getMessage());
        }

    }

    public static class MockBuildTriggerThrowsNPEOnStart extends Trigger<Item> {
        @Override
        public void start(hudson.model.Item project, boolean newInstance) {
            throw new NullPointerException();
        }

        @TestExtension("loadingWithNPEOnTriggerStart")
        public static class DescriptorImpl extends TriggerDescriptor {

            @Override
            public boolean isApplicable(hudson.model.Item item) {
                return false;
            }
        }

    }

    @Issue("SECURITY-617")
    @Test
    public void upstreamDownstreamExportApi() throws Exception {
        FreeStyleProject us = j.createFreeStyleProject("upstream-project");
        FreeStyleProject ds = j.createFreeStyleProject("downstream-project");
        us.getPublishersList().add(new BuildTrigger(Collections.singleton(ds), Result.SUCCESS));
        j.jenkins.rebuildDependencyGraph();
        assertEquals(Collections.singletonList(ds), us.getDownstreamProjects());
        assertEquals(Collections.singletonList(us), ds.getUpstreamProjects());
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
                grant(Jenkins.READ).everywhere().toEveryone().
                grant(Item.READ).everywhere().to("alice").
                grant(Item.READ).onItems(us).to("bob").
                grant(Item.READ).onItems(ds).to("charlie"));
        String api = j.createWebClient().withBasicCredentials("alice").goTo(us.getUrl() + "api/json?pretty", null).getWebResponse().getContentAsString();
        System.out.println(api);
        assertThat(api, containsString("downstream-project"));
        api = j.createWebClient().withBasicCredentials("alice").goTo(ds.getUrl() + "api/json?pretty", null).getWebResponse().getContentAsString();
        System.out.println(api);
        assertThat(api, containsString("upstream-project"));
        api = j.createWebClient().withBasicCredentials("bob").goTo(us.getUrl() + "api/json?pretty", null).getWebResponse().getContentAsString();
        System.out.println(api);
        assertThat(api, not(containsString("downstream-project")));
        api = j.createWebClient().withBasicCredentials("charlie").goTo(ds.getUrl() + "api/json?pretty", null).getWebResponse().getContentAsString();
        System.out.println(api);
        assertThat(api, not(containsString("upstream-project")));
    }

}
