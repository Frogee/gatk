package org.broadinstitute.sting.playground.gatk.walkers.variants;

import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.refdata.rodVariants;

public class VECNull implements VariantExclusionCriterion {
    public void initialize(String arguments) {
    }

    public void compute(char ref, AlignmentContext context, rodVariants variant) {
    }

    public boolean isExcludable() {
        return false;
    }

    public String getStudyHeader() {
        return "";
    }

    public String getStudyInfo() {
        return "";
    }

    public boolean useZeroQualityReads() {
        return false;
    }
}
