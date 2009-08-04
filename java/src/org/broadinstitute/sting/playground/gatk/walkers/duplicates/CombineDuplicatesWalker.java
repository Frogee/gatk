package org.broadinstitute.sting.playground.gatk.walkers;

import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.walkers.DuplicateWalker;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.duplicates.DupUtils;
import org.broadinstitute.sting.utils.cmdLine.Argument;

import java.util.List;

import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMFileWriter;

/**
 * Created by IntelliJ IDEA.
 * User: mdepristo
 * Date: Feb 22, 2009
 * Time: 2:52:28 PM
 * To change this template use File | Settings | File Templates.
 */
public class CombineDuplicatesWalker extends DuplicateWalker<SAMRecord, SAMFileWriter> {
    @Argument(fullName="outputBAM", shortName="outputBAM", required=false, doc="BAM File to write combined duplicates to")
    public SAMFileWriter outputBAM = null;

    @Argument(fullName="includeUniqueReads", shortName="includeUniqueReads", required=false, doc="If true, also writes out non-duplicate reads in file")
    public boolean INCLUDE_UNIQUE_READS = true;

    @Argument(fullName="maxQ", shortName="maxQ", required=false,
            doc="The maximum Q score allowed for combined reads, reflects the background error rate giving rise to perfect bases that don't correspond to the reference")
    public int MAX_QUALITY_SCORE = 50;

    final boolean DEBUG = false;

    public boolean mapUniqueReadsTooP() {
        return INCLUDE_UNIQUE_READS;
    }

    // -----------------------------------------------------------------------------------------------
    // Standard i/o reduce
    //
    public void onTraversalDone(SAMFileWriter output) {
        if ( output != null ) {
            output.close();
        }
    }

    public SAMFileWriter reduceInit() {
        return outputBAM;
    }

    /**
     * 
     *
     */
    public SAMFileWriter reduce(SAMRecord read, SAMFileWriter output) {
        if ( output != null ) {
            output.addAlignment(read);
        } else {
            out.println(read.format());
        }

        return output;
    }

    /**
     * Build a combined read given the input list of non-unique reads.  If there's just one read in the
     * set, it's considered unique and returned.  If there's more than one, the N-way combine
     * duplicate function is invoked.
     *
     * @param loc
     * @param refBases
     * @param context
     * @param duplicateReads
     * @return
     */
    public SAMRecord map(GenomeLoc loc, byte[] refBases, AlignmentContext context,
                         List<SAMRecord> uniqueReads,
                         List<SAMRecord> duplicateReads) {
        //logger.info(String.format("%s has %d duplicates and %d non-duplicates", loc, duplicateReads.size(), uniqueReads.size()));

        SAMRecord combinedRead = null;

        if ( duplicateReads.size() == 1 && ! duplicateReads.get(0).getDuplicateReadFlag() ) {
            // we are a unique read
            combinedRead = duplicateReads.get(0);
        } else {
            // actually call the combine function
            //for (SAMRecord read : duplicateReads ) {
            //    System.out.printf("Read %s%n", read.format());
            //}
            combinedRead = DupUtils.combineDuplicates(duplicateReads, MAX_QUALITY_SCORE);
        }

        if ( combinedRead.getDuplicateReadFlag() )
            throw new RuntimeException(String.format("Combined read %s [of %d] is a duplicate after combination -- this is a bug%n%s",
                    combinedRead.getReadName(), duplicateReads.size(), combinedRead.format()));
        
        return combinedRead;
    }
}