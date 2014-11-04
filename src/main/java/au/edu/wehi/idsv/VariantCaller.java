package au.edu.wehi.idsv;

import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordQueryNameComparator;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFFileReader;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.google.common.base.Function;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;


/**
 * Calls structural variant
 * @author cameron.d
 *
 */
public class VariantCaller extends EvidenceProcessorBase {
	private static final Log log = Log.getInstance(VariantCaller.class);
	public VariantCaller(ProcessingContext context, File output, List<EvidenceSource> evidence) {
		super(context, output, evidence);
	}
	@Override
	public void process() {
		callBreakends(null);
		annotateBreakpoints();
		writeOutput();
	}
	private static class WriteMaximalCliquesForChromosome extends EvidenceProcessorBase implements Callable<Void> {
		private int chri;
		private int chrj;
		public WriteMaximalCliquesForChromosome(ProcessingContext context, File output, List<EvidenceSource> evidence, int chri, int chrj) {
			super(context, output, evidence);
			this.chri = chri;
			this.chrj = chrj;
		}
		@Override
		public Void call() {
			final SAMSequenceDictionary dict = processContext.getReference().getSequenceDictionary();
			final String iname = dict.getSequence(chri).getSequenceName();
			final String jname = dict.getSequence(chrj).getSequenceName();
			String task = "Identifying Breakpoints between "  + iname + " and " + jname;
			try {
				log.info("Start " + task);
				EvidenceClusterSubsetProcessor processor = new EvidenceClusterSubsetProcessor(processContext, chri, chrj);
				writeMaximalCliquesToVcf(processContext,
						processor,
						processContext.getFileSystemContext().getBreakpointVcf(output, iname, jname),
						getEvidenceForChr(chri, chrj));
				log.info("Complete " + task);
			} catch (Exception e) {
				log.error(e, "Error " + task);
				throw new RuntimeException("Error " + task, e);
			}
			return null;
		}
		@Override
		public void process() {
			call();
		}
	}
	public void callBreakends(ExecutorService threadpool) {
		log.info("Identifying Breakpoints");
		try {
			if (processContext.shouldProcessPerChromosome()) {
				List<WriteMaximalCliquesForChromosome> workers = Lists.newArrayList();
				final SAMSequenceDictionary dict = processContext.getReference().getSequenceDictionary();
				for (int i = 0; i < dict.size(); i++) {					
					for (int j = i; j < dict.size(); j++) {
						workers.add(new WriteMaximalCliquesForChromosome(processContext, output, evidence,  i, j));
					}
				}
				if (threadpool != null) {
					log.info("Identifying Breakpoints in parallel");
					try {
						for (Future<Void> future : threadpool.invokeAll(workers)) {
							// throw exception from worker thread here
							future.get();
						}
					} catch (InterruptedException e) {
						log.error(e, "Interrupted Identifying Breakpoints");
						throw new RuntimeException(e);
					} catch (ExecutionException e) {
						log.error(e, "Error Identifying Breakpoints");
						throw new RuntimeException(e);
					}
				} else {
					log.info("Identifying Breakpoints sequentially");
					for (WriteMaximalCliquesForChromosome c : workers) {
						c.call();
					}
				}
			} else {
				EvidenceClusterProcessor processor = new EvidenceClusterProcessor(processContext);
				writeMaximalCliquesToVcf(
						processContext,
						processor,
						processContext.getFileSystemContext().getBreakpointVcf(output),
						getAllEvidence());
			}
		} finally {
			close();
		}
	}
	private List<Closeable> toClose = Lists.newArrayList();
	private Iterator<IdsvVariantContext> getAllCalledVariants() {
		List<Iterator<IdsvVariantContext>> variants = Lists.newArrayList();
		if (processContext.shouldProcessPerChromosome()) {
			SAMSequenceDictionary dict = processContext.getReference().getSequenceDictionary();
			for (int i = 0; i < dict.size(); i++) {
				final String chri = dict.getSequence(i).getSequenceName();
				for (int j = i; j < dict.size(); j++) {
					final String chrj = dict.getSequence(j).getSequenceName();
					variants.add(getVariants(processContext.getFileSystemContext().getBreakpointVcf(output, chri, chrj)));
				}
			}
		} else {
			variants.add(getVariants(processContext.getFileSystemContext().getBreakpointVcf(output)));
		}
		Iterator<IdsvVariantContext> merged = Iterators.mergeSorted(variants, IdsvVariantContext.ByLocationStart);
		return merged;
	}
	public void close() {
		super.close();
		for (Closeable c : toClose) {
			if (c != null) {
				try {
					c.close();
				} catch (IOException e) {
					log.error(e, " error closing ", c);
				}
			}
		}
		toClose.clear();
	}
	private Iterator<IdsvVariantContext> getVariants(File file) {
		VCFFileReader vcfReader = new VCFFileReader(file, false);
		toClose.add(vcfReader);
		CloseableIterator<VariantContext> it = vcfReader.iterator();
		toClose.add(it);
		Iterator<IdsvVariantContext> idsvIt = Iterators.transform(it, new Function<VariantContext, IdsvVariantContext>() {
			@Override
			public IdsvVariantContext apply(VariantContext arg) {
				return IdsvVariantContext.create(processContext, null, arg);
			}
		});
		return idsvIt;
	}
	private static void writeMaximalCliquesToVcf(ProcessingContext processContext, EvidenceClusterProcessor processor, File vcf, Iterator<DirectedEvidence> evidenceIt) {
		final ProgressLogger writeProgress = new ProgressLogger(log);
		log.info("Loading minimal evidence set for ", vcf, " into memory.");
		while (evidenceIt.hasNext()) {
			processor.addEvidence(evidenceIt.next());
		}
		VariantContextWriter vcfWriter = null;
		try {
			vcfWriter = processContext.getVariantContextWriter(vcf);
			log.info("Start calling maximal cliques for ", vcf);
			Iterator<VariantContextDirectedEvidence> it = processor.iterator();
			while (it.hasNext()) {
				VariantContextDirectedEvidence loc = it.next();
				vcfWriter.add(loc);
				writeProgress.record(processContext.getDictionary().getSequence(loc.getBreakendSummary().referenceIndex).getSequenceName(), loc.getBreakendSummary().start);
			}
			log.info("Complete calling maximal cliques for ", vcf);
		} finally {
			if (vcfWriter != null) vcfWriter.close();
		}
	}
	public void annotateBreakpoints() {
		annotateBreakpoints(null);
	}
	public void annotateBreakpoints(BreakendAnnotator annotator) {
		log.info("Annotating Calls");
		List<File> normalFiles = Lists.newArrayList();
		List<File> tumourFiles = Lists.newArrayList();
		for (EvidenceSource source : evidence) {
			if (source instanceof SAMEvidenceSource) {
				SAMEvidenceSource samSource = (SAMEvidenceSource)source;
				if (samSource.isTumour()) {
					tumourFiles.add(samSource.getSourceFile());
				} else {
					normalFiles.add(samSource.getSourceFile());
				}
			}
		}
		try {
			final VariantContextWriter vcfWriter = processContext.getVariantContextWriter(output);
			toClose.add(vcfWriter);
			final SequentialReferenceCoverageLookup normalCoverage = getReferenceLookup(normalFiles);
			toClose.add(normalCoverage);
			final SequentialReferenceCoverageLookup tumourCoverage = getReferenceLookup(tumourFiles);
			toClose.add(tumourCoverage);
			BreakendAnnotator referenceAnnotator  = new SequentialCoverageAnnotator(processContext, normalCoverage, tumourCoverage);
			BreakendAnnotator evidenceAnnotator = new SequentialEvidenceAnnotator(processContext, getAllEvidence());
			Iterator<IdsvVariantContext> it = getAllCalledVariants();
			while (it.hasNext()) {
				IdsvVariantContext rawVariant = it.next();
				if (rawVariant instanceof VariantContextDirectedEvidence && ((VariantContextDirectedEvidence)rawVariant).isValid()) {
					VariantContextDirectedEvidence annotatedVariant = evidenceAnnotator.annotate(referenceAnnotator.annotate((VariantContextDirectedEvidence)rawVariant));
					if (annotator != null) {
						annotatedVariant = annotator.annotate(annotatedVariant);
					}
					vcfWriter.add(annotatedVariant);
				}
			}
			vcfWriter.close();
			normalCoverage.close();
			tumourCoverage.close();
			log.info("Variant calls written to ", output);
		} finally {
			close();
		}
	}
	public SequentialReferenceCoverageLookup getReferenceLookup(List<File> samFiles) {
		List<CloseableIterator<SAMRecord>> toMerge = Lists.newArrayList();
		for (File f : samFiles) {
			SamReader reader = processContext.getSamReader(f);
			toClose.add(reader);
			CloseableIterator<SAMRecord> it = processContext.getSamReaderIterator(reader, SortOrder.coordinate);
			toClose.add(it);
			it = processContext.applyCommonSAMRecordFilters(it);
			toMerge.add(it);
		}
		Iterator<SAMRecord> merged = Iterators.mergeSorted(toMerge, new SAMRecordQueryNameComparator()); 
		return new SequentialReferenceCoverageLookup(merged, 1024);
	}
	private void writeOutput() {
		log.info("Outputting calls to ", output);
	}
}