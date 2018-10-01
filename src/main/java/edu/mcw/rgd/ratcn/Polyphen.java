package edu.mcw.rgd.ratcn;

import edu.mcw.rgd.dao.impl.SampleDAO;
import edu.mcw.rgd.dao.impl.SequenceDAO;
import edu.mcw.rgd.datamodel.Sample;
import edu.mcw.rgd.datamodel.Sequence2;
import org.springframework.beans.factory.xml.XmlBeanFactory;
import org.springframework.core.io.FileSystemResource;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * @author mtutaj
 * @since 11/20/13
 */
public class Polyphen extends VariantProcessingBase {

    private String version;

    private String WORKING_DIR = "/data/rat/output";
    private BufferedWriter errorFile;
    private BufferedWriter polyphenFile;
    private BufferedWriter polyphenFileInfo;
    private BufferedWriter fastaFile;

    SequenceDAO sequenceDAO = new SequenceDAO();
    SampleDAO sampleDAO = new SampleDAO();
    String varTable = "VARIANT";
    String varTrTable = "VARIANT_TRANSCRIPT";
    boolean simpleProteinQC = false;
    boolean createFastaFile = false;

    public Polyphen() throws Exception {

    }

    public static void main(String[] args) throws Exception {

        XmlBeanFactory bf=new XmlBeanFactory(new FileSystemResource("properties/AppConfigure.xml"));
        Polyphen instance = (Polyphen) (bf.getBean("polyphen"));

        instance.sampleDAO.setDataSource(instance.getDataSource());

        // process args
        int sampleId = 0;
        String chr = null;

        for( int i=0; i<args.length; i++ ) {
            if( args[i].equals("--sample") ) {
                sampleId = Integer.parseInt(args[++i]);
            }
            else if( args[i].equals("--chr") ) {
                chr = args[++i];
            }
            else if( args[i].equals("--outDir") ) {
                instance.WORKING_DIR = args[++i];
            }
            else if( args[i].equals("--fasta") ) {
                instance.createFastaFile = true;
            }
        }

        if( chr==null )
            instance.run(sampleId);
        else
            instance.run(sampleId, chr);

        instance.getLogWriter().close();
    }

    public void run(int sampleId) throws Exception {

        String[] chromosomes = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "X",
        "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "Y",};

        String fileNameBase = WORKING_DIR + "/" + sampleId;
        this.setLogWriter(new BufferedWriter(new FileWriter(fileNameBase+".log")));

        for( String chr: chromosomes ) {
            run(sampleId, chr);
        }
    }

    public void run(int sampleId, String chr) throws Exception {

        if( sampleId<100 ) {
            varTable = "VARIANT_HUMAN";
            varTrTable = "VARIANT_TRANSCRIPT_HUMAN";
            simpleProteinQC = true;
        } else {
            varTable = "VARIANT";
            varTrTable = "VARIANT_TRANSCRIPT";
            simpleProteinQC = false;
        }

        if( this.getLogWriter()==null ) {
            String fileNameBase = WORKING_DIR + "/" + sampleId + "." + chr;
            this.setLogWriter(new BufferedWriter(new FileWriter(fileNameBase+".log")));
        }

        String errorFileName = WORKING_DIR + "/ErrorFile_Sample" + sampleId + "."+chr+".PolyPhen.error";
        errorFile = new BufferedWriter(new FileWriter(errorFileName));

        String polyphenFileName = WORKING_DIR + "/Sample" + sampleId + "."+chr+".PolyPhenInput";
        this.getLogWriter().write("starting "+polyphenFileName+"\n");
        polyphenFile = new BufferedWriter(new FileWriter(polyphenFileName));
        polyphenFileInfo = new BufferedWriter(new FileWriter(polyphenFileName+".info"));
        polyphenFileInfo.append("#Note: if STRAND is '-', then inverted NUC_VAR is AA_REF\n");
        polyphenFileInfo.append("#VARIANT_ID\tVARIANT_TRANSCRIPT_ID\tLOCUS_NAME\tPROTEIN_ACC_ID\tRELATIVE_VAR_POS\tREF_AA\tVAR_AA\tSTRAND\tTRANSCRIPT_RGD_ID\n");

        Sample sample = sampleDAO.getSampleBySampleId(sampleId);
        int mapKey = sample.getMapKey();

        if( createFastaFile ) {
            // create the fasta file
            String fastaFileName = WORKING_DIR + "/Sample" + sampleId + "." + chr + ".PolyPhenInput.fasta";
            fastaFile = new BufferedWriter(new FileWriter(fastaFileName));
        }

        runSample(sampleId, mapKey, chr);

        polyphenFileInfo.close();
        polyphenFile.close();
        errorFile.close();
        if( fastaFile!=null ) {
            fastaFile.close();
        }

        this.getLogWriter().write("finishing "+polyphenFileName+"\n\n\n");
    }

    public void runSample(int sampleId, int mapKey, String chr) throws Exception {

        int variantsProcessed = 0;
        int refSeqProteinLengthErrors = 0;
        int refSeqProteinLeftPartMismatch = 0;
        int refSeqProteinRightPartMismatch = 0;
        int proteinRefSeqNotInRgd = 0;
        int stopCodonsInProtein = 0;

        // /*+ INDEX(vt) */
        // this query hint forces oracle to use indexes on VARIANT_TRANSCRIPT table
        // (many times it was using full scans for unknown reasons)

        String sql = "SELECT /*+ INDEX(vt) */ \n" +
        "vt.variant_transcript_id, v.start_pos, g.gene_symbol as region_name, t.gene_rgd_id, \n" +
        "v.ref_nuc, v.var_nuc, vt.ref_aa, vt.var_aa, vt.full_ref_aa, vt.full_ref_aa_pos, \n" +
        "t.acc_id, t.protein_acc_id, vt.transcript_rgd_id, v.variant_id\n" +
        "FROM "+varTable+" v, "+varTrTable+" vt, transcripts t, genes g\n" +
        "WHERE vt.ref_aa <> vt.var_aa  AND  vt.var_aa<>'*' \n" +
        "AND v.ref_nuc IN ('A', 'G', 'C', 'T') \n" +
        "AND v.var_nuc IN ('A', 'G', 'C', 'T') \n" +
        "AND vt.ref_aa IS NOT NULL  AND  vt.var_aa IS NOT NULL \n" +
        "AND v.sample_id = ? \n" +
        "AND v.chromosome = ? \n" +
        "AND v.variant_id = vt.variant_id \n" +
        "AND t.transcript_rgd_id=vt.transcript_rgd_id \n" +
        "AND t.gene_rgd_id=g.rgd_id";

        Connection conn = this.getDataSource().getConnection();
        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setInt(1, sampleId);
        ps.setString(2, chr);
        ResultSet rs = ps.executeQuery();
        int lineNr = 0;
        String line;
        while( rs.next() ) {
            lineNr++;
            long variantTranscriptId = rs.getLong(1);
            int startPos = rs.getInt(2);
            String regionName = rs.getString(3);
            //int geneRgdId = rs.getInt(4);
            //String refNuc = rs.getString(5);
            //String varNuc = rs.getString(6);
            String refAA = rs.getString(7);
            String varAA = rs.getString(8);
            String fullRefAA = rs.getString(9);
            int fullRefAaaPos = rs.getInt(10);
            //String nucAccId = rs.getString(11);
            String proteinAccId = rs.getString(12);
            int transcriptRgdId = rs.getInt(13);
            long variantId = rs.getLong(14);

            String strand = getStrand(transcriptRgdId, chr, startPos, mapKey);

            this.getLogWriter().append("\n\nChr " + chr + " line " + lineNr + "\n" +
                    "    variant_id = " + variantId + "\n" +
                    "    transcript_rgd_id = " + transcriptRgdId + "\n" +
                    "    protein_acc_id = " + proteinAccId + "\n" +
                    "    ref_aa_pos = " + fullRefAaaPos + "\n" +
                    "    ref_aa = " + refAA + "\n" +
                    "    var_aa = " + varAA + "\n" +
                    "    region_name = " + regionName + "\n" +
                    "    strand = " + strand + "\n");

            if( simpleProteinQC ) {

                // simple protein QC:
                // there must not be any stop codons in the middle of the protein
                // only at the end, if any
                int stopCodonFirstPos = fullRefAA.indexOf('*');
                if( stopCodonFirstPos < fullRefAA.length()-1 ) {
                    // stop codons in the middle of the protein

                    // exception: if stop codons are 10 AAs after variant pos, that's OK
                    if( stopCodonFirstPos <= fullRefAaaPos+10 ) {
                        line = "stop codons in the middle of the protein sequence!\n" +
                                "    transcript_rgd_id = " + transcriptRgdId + "\n" +
                                "    protein_acc_id = " + proteinAccId + "\n" +
                                "    ref_aa_pos = " + fullRefAaaPos + "\n" +
                                "    RefSeq protein length = " + fullRefAA.length() + "\n";
                        errorFile.append(line);
                        stopCodonsInProtein++;
                        this.getLogWriter().append("***STOP CODONS IN PROTEIN***\n" + line);
                        continue;
                    }
                }

                variantsProcessed++;

                String translatedLeftPart = fullRefAA.substring(0, fullRefAaaPos-1);

                // RefSeq protein part to the right of the mutation point must match the translated part
                String translatedRightPart = fullRefAA.substring(fullRefAaaPos);
                if( translatedRightPart.endsWith("*") )
                    translatedRightPart = translatedRightPart.substring(0, translatedRightPart.length()-1);

                this.getLogWriter().append("***MATCH***\n"+
                        "  proteinLeftPart\n" + translatedLeftPart+"\n"+
                        "  proteinRightPart\n" + translatedRightPart+"\n");

                // write polyphen input file
                // PROTEIN_ACC_ID POS REF_AA VAR_AA
                line = proteinAccId+" "+fullRefAaaPos+" "+refAA+" "+varAA+"\n";
                polyphenFile.write(line);

                // write polyphen input info file
                //#VARIANT_ID\tVARIANT_TRANSCRIPT_ID\tLOCUS_NAME\tPROTEIN_ACC_ID\tRELATIVE_VAR_POS\tREF_AA\tVAR_AA\tSTRAND\tTRANSCRIPT_RGD_ID");
                line = variantId+"\t"+variantTranscriptId+"\t"+regionName+"\t"+proteinAccId+"\t"+fullRefAaaPos+"\t"+refAA+"\t"+varAA+"\t"+strand+"\t"+transcriptRgdId+"\n";
                polyphenFileInfo.write(line);

                writeFastaFile(proteinAccId, fullRefAA);
                continue;
            }

            // retrieve protein sequence from RGD
            List<Sequence2> seqsInRgd = getProteinSequences(transcriptRgdId);
            if( seqsInRgd==null || seqsInRgd.isEmpty() ) {
                proteinRefSeqNotInRgd++;
                this.getLogWriter().append("***PROTEIN REFSEQ NOT IN RGD***\n");
            }
            else {
                for( Sequence2 seq: seqsInRgd ) {
                    variantsProcessed++;

                    // RefSeq protein part to the left of the mutation point must match the translated part
                    String refSeqLeftPart;
                    String translatedLeftPart = fullRefAA.substring(0, fullRefAaaPos-1);
                    try {
                        refSeqLeftPart = seq.getSeqData().substring(0, fullRefAaaPos-1);
                    } catch(IndexOutOfBoundsException e) {
                        line = "RefSeq protein shorter than REF_AA_POS!\n" +
                                "    transcript_rgd_id = " + transcriptRgdId + "\n" +
                                "    protein_acc_id = " + proteinAccId + "\n" +
                                "    ref_aa_pos = " + fullRefAaaPos + "\n" +
                                "    RefSeq protein length = " + seq.getSeqData().length() + "\n";
                        errorFile.append(line);
                        refSeqProteinLengthErrors++;
                        this.getLogWriter().append("***LEFT FLANK LENGTH ERROR***\n" + line);
                        continue;
                    }

                    if( !refSeqLeftPart.equalsIgnoreCase(translatedLeftPart) ) {
                        line = "Left flank not the same!\n" +
                                "    transcript_rgd_id = " + transcriptRgdId + "\n" +
                                "    protein_acc_id = " + proteinAccId + "\n" +
                                "    RefSeq left part         " + refSeqLeftPart + "\n" +
                                "    translated ref left part " + translatedLeftPart + "\n";
                        errorFile.append(line);
                        refSeqProteinLeftPartMismatch++;
                        this.getLogWriter().append("***LEFT FLANK ERROR***\n"+line);
                        continue;
                    }


                    // RefSeq protein part to the right of the mutation point must match the translated part
                    String refSeqRightPart;
                    String translatedRightPart = fullRefAA.substring(fullRefAaaPos);
                    if( translatedRightPart.endsWith("*") )
                        translatedRightPart = translatedRightPart.substring(0, translatedRightPart.length()-1);
                    try {
                        refSeqRightPart = seq.getSeqData().substring(fullRefAaaPos);
                    } catch(IndexOutOfBoundsException e) {
                        line = "RefSeq protein shorter than REF_AA_POS!\n" +
                                "    transcript_rgd_id = " + transcriptRgdId + "\n" +
                                "    protein_acc_id = " + proteinAccId + "\n" +
                                "    ref_aa_pos = " + fullRefAaaPos + "\n" +
                                "    RefSeq protein length = " + seq.getSeqData().length() + "\n";
                        errorFile.append(line);
                        refSeqProteinLengthErrors++;
                        this.getLogWriter().append("***RIGHT FLANK LENGTH ERROR***\n"+line);
                        continue;
                    }

                    if( !refSeqRightPart.equalsIgnoreCase(translatedRightPart) ) {
                        line = "Right flank not the same!\n" +
                                "    transcript_rgd_id = " + transcriptRgdId + "\n" +
                                "    protein_acc_id = " + proteinAccId + "\n" +
                                "    RefSeq left part         " + refSeqRightPart + "\n" +
                                "    translated ref right part " + translatedRightPart + "\n";
                        errorFile.append(line);
                        refSeqProteinRightPartMismatch++;
                        this.getLogWriter().append("***RIGHT FLANK ERROR***\n"+line);
                        continue;
                    }

                    this.getLogWriter().append("***MATCH***\n"+
                            "  proteinLeftPart\n" + refSeqLeftPart+"\n"+
                            "  proteinRightPart\n" + refSeqRightPart+"\n");

                    // write polyphen input file
                    // PROTEIN_ACC_ID POS REF_AA VAR_AA
                    line = proteinAccId+" "+fullRefAaaPos+" "+refAA+" "+varAA+"\n";
                    polyphenFile.write(line);

                    // write polyphen input info file
                    //#VARIANT_ID\tVARIANT_TRANSCRIPT_ID\tLOCUS_NAME\tPROTEIN_ACC_ID\tRELATIVE_VAR_POS\tREF_AA\tVAR_AA\tSTRAND\tTRANSCRIPT_RGD_ID");
                    line = variantId+"\t"+variantTranscriptId+"\t"+regionName+"\t"+proteinAccId+"\t"+fullRefAaaPos+"\t"+refAA+"\t"+varAA+"\t"+strand+"\t"+transcriptRgdId+"\n";
                    polyphenFileInfo.write(line);

                    writeFastaFile(proteinAccId, fullRefAA);
                }
            }
        }

        conn.close();

        getLogWriter().write("\nPROCESSING SUMMARY:");
        getLogWriter().write("\n  variantsProcessed = "+variantsProcessed);
        getLogWriter().write("\n  refSeqProteinLengthErrors = "+refSeqProteinLengthErrors);
        getLogWriter().write("\n  refSeqProteinLeftPartMismatch = "+refSeqProteinLeftPartMismatch);
        getLogWriter().write("\n  refSeqProteinRightPartMismatch = "+refSeqProteinRightPartMismatch);
        getLogWriter().write("\n  proteinRefSeqNotInRgd = "+proteinRefSeqNotInRgd);
        getLogWriter().write("\n  stopCodonsInProtein = "+stopCodonsInProtein);
        getLogWriter().newLine();
    }

    void writeFastaFile(String proteinAccId, String fullRefAA) throws IOException {
        if( fastaFile!=null ) {
            fastaFile.write(">"+proteinAccId);
            fastaFile.newLine();

            // write protein sequence, up to 70 characters per line
            for( int i=0; i<fullRefAA.length(); i+=70 ) {
                int chunkEndPos = i+70;
                if( chunkEndPos > fullRefAA.length() )
                    chunkEndPos = fullRefAA.length();
                String chunk = fullRefAA.substring(i, chunkEndPos);
                fastaFile.write(chunk);
                fastaFile.newLine();
            }
        }
    }

    List<Sequence2> getProteinSequences(int transcriptRgdId) throws Exception {

        List<Sequence2> seqsInRgd = sequenceDAO.getObjectSequences2(transcriptRgdId, "ncbi_protein");
        return seqsInRgd;
    }

    String getStrand(int rgdId, String chr, int pos, int mapKey) throws Exception {

        String strands = "";

        String sql = "SELECT DISTINCT strand FROM maps_data md "+
                "WHERE md.rgd_id=? AND map_key=? AND chromosome=? AND start_pos<=? AND stop_pos>=?";

        Connection conn = this.getDataSource().getConnection();
        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setInt(1, rgdId);
        ps.setInt(2, mapKey);
        ps.setString(3, chr);
        ps.setInt(4, pos);
        ps.setInt(5, pos);
        ResultSet rs = ps.executeQuery();
        while( rs.next() ) {
            String strand = rs.getString(1);
            if( strand != null )
                strands += strand;
        }

        conn.close();
        return strands;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }
}
