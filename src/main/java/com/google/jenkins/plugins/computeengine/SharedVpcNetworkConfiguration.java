package com.google.jenkins.plugins.computeengine;

import com.google.common.base.Strings;
import hudson.Extension;
import hudson.RelativePath;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class SharedVpcNetworkConfiguration extends NetworkConfiguration {
    public static final String SUBNETWORK_TEMPLATE = "projects/%s/regions/%s/subnetworks/%s";
    public final String projectId;
    public final String subnetworkShortName;
    public final String region;

    @DataBoundConstructor
    public SharedVpcNetworkConfiguration(String projectId, String region, String subnetworkShortName) {
        super("", String.format(SUBNETWORK_TEMPLATE, projectId, region, subnetworkShortName));
        this.projectId = projectId;
        this.subnetworkShortName = subnetworkShortName;
        this.region = region;
    }

    @Extension
    public static final class DescriptorImpl extends NetworkConfigurationDescriptor {
        public String getDisplayName() {
            return "Shared VPC";
        }

        public FormValidation doCheckProjectId(@QueryParameter String value) {
            if (Strings.isNullOrEmpty(value)) {
                return FormValidation.error("Project ID required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckSubnetworkName(@QueryParameter String value) {
            if (Strings.isNullOrEmpty(value)) {
                return FormValidation.error("Subnetwork name required");
            }

            if (value.contains("/")) {
                return FormValidation.error("Subnetwork name should not contain any '/' characters");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckRegion(@QueryParameter String value, @QueryParameter("region") @RelativePath("..") final String region) {
            if (Strings.isNullOrEmpty(region) || Strings.isNullOrEmpty(value) || !region.endsWith(value)) {
                return FormValidation.error("The region you specify for a shared VPC should match the region selected in the 'Location' section above");
            }
            return FormValidation.ok();
        }
    }
}
