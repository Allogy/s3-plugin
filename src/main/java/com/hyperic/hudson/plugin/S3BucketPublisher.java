package com.hyperic.hudson.plugin;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.tasks.Publisher;
import hudson.util.CopyOnWriteList;
import hudson.util.FormFieldValidator;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.jets3t.service.S3ServiceException;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

public final class S3BucketPublisher extends Publisher {

    private String profileName;
    public static final Logger LOGGER =
        Logger.getLogger(S3BucketPublisher.class.getName());

    private final List<Entry> entries = new ArrayList<Entry>();

    public S3BucketPublisher() {
    }
        
    public S3BucketPublisher(String profileName) {
        if (profileName == null) {
            // defaults to the first one
            S3Profile[] sites = DESCRIPTOR.getProfiles();
            if (sites.length > 0)
                profileName = sites[0].getName();
        }
        this.profileName = profileName;
    }

    public List<Entry> getEntries() {
        return entries;
    }
        
    public S3Profile getProfile() {
        S3Profile[] profiles = DESCRIPTOR.getProfiles();
        if (profileName == null && profiles.length > 0)
            // default
            return profiles[0];

        for (S3Profile profile : profiles) {
            if (profile.getName().equals(profileName))
                return profile;
        }
        return null;
    }

    public boolean perform(AbstractBuild<?, ?> build,
                           Launcher launcher,
                           BuildListener listener)
        throws InterruptedException, IOException {

        if (build.getResult() == Result.FAILURE) {
            // build failed. don't post
            return true;
        }

        S3Profile profile = getProfile();
        if (profile == null) {
            log(listener.getLogger(), "No S3 profile is configured.");
            build.setResult(Result.UNSTABLE);
            return true;
        }
        log(listener.getLogger(), "Using S3 profile: " + profile.getName());
        try {
            profile.login();
        } catch (S3ServiceException e) {
            throw new IOException("Can't connect to S3 service: " + e);
        }

        try {
            Map<String, String> envVars = build.getEnvVars();

            for (Entry entry : entries) {
                String expanded = Util.replaceMacro(entry.sourceFile, envVars);
                FilePath ws = build.getProject().getWorkspace();
                FilePath[] paths = ws.list(expanded);

                if (paths.length == 0) {
                    // try to do error diagnostics
                    log(listener.getLogger(), "No file(s) found: " + expanded);
                    String error = ws.validateAntFileMask(expanded);
                    if (error != null)
                        log(listener.getLogger(), error);
                }
                String bucket = Util.replaceMacro(entry.bucket, envVars);
                for (FilePath src : paths) {
                    log(listener.getLogger(), "bucket=" + bucket + ", file=" + src.getName());
                    profile.upload(bucket, src, envVars, listener.getLogger());
                }
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error("Failed to upload files"));
            build.setResult(Result.UNSTABLE);
        } finally {
            if (profile != null) {
                profile.logout();
            }
        }

        return true;
    }

    public Descriptor<Publisher> getDescriptor() {
        return DESCRIPTOR;
    }

    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
        
    public static final class DescriptorImpl extends Descriptor<Publisher> {
                
        public DescriptorImpl() {
            super(S3BucketPublisher.class);
            load();
        }
                
        protected DescriptorImpl(Class<? extends Publisher> clazz) {
            super(clazz);
        }

        private final CopyOnWriteList<S3Profile> profiles = new CopyOnWriteList<S3Profile>();
                
        public String getDisplayName() {
            return "Publish artifacts to S3 Bucket";
        }
                
        public String getShortName()
        {
            return "[S3] ";
        }

        public String getHelpFile() {
            return "/plugin/s3/help.html";
        }

        public Publisher newInstance(StaplerRequest req) {
            S3BucketPublisher pub = new S3BucketPublisher();
            req.bindParameters(pub, "s3.");
            pub.getEntries().addAll(
            req.bindParametersToList(Entry.class, "s3.entry."));
            return pub;
        }
                
        public S3Profile[] getProfiles() {
            return profiles.toArray(new S3Profile[0]);
        }

        public boolean configure(StaplerRequest req) {
            profiles.replaceBy(req.bindParametersToList(S3Profile.class, "s3."));
            save();
            return true;
        }
                
        public void doLoginCheck(final StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
            new FormFieldValidator(req, rsp, false) {
                protected void check() throws IOException, ServletException {
                    String name =
                        Util.fixEmpty(request.getParameter("name"));
                    if (name == null) {// name is not entered yet
                        ok();
                        return;
                    }
                    S3Profile profile =
                        new S3Profile(name,
                                      request.getParameter("accessKey"),
                                      request.getParameter("secretKey"));
                    try {
                        try {
                            profile.login();
                            profile.check();
                            profile.logout();
                        } catch (S3ServiceException e) {
                            LOGGER.log(Level.SEVERE, e.getMessage());
                            throw new IOException("Can't connect to S3 service: " +
                                                  e.getS3ErrorMessage());
                        }
                                                
                        ok();
                    } catch (IOException e) {
                        LOGGER.log(Level.SEVERE, e.getMessage());
                        error(e.getMessage());
                    }
                }
            }.process();
        }
    }

    public String getProfileName() {
        return this.profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    protected void log(final PrintStream logger, final String message) {
        logger.println(StringUtils.defaultString(DESCRIPTOR.getShortName()) + message);
    }
}
