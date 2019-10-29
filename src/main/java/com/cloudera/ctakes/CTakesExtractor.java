package com.cloudera.ctakes;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.ctakes.pipelines.CTakesResult;
import org.apache.ctakes.pipelines.RushEndToEndPipeline;
import org.apache.ctakes.utils.RushFileUtils;
import org.apache.curator.shaded.com.google.common.base.Throwables;
import org.apache.pig.EvalFunc;
import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.logicalLayer.schema.Schema.FieldSchema;
import org.apache.pig.impl.util.UDFContext;
import org.apache.tools.ant.util.StringUtils;
import org.apache.uima.collection.metadata.CpeDescription;

/**
 * Pig UDF to process text through cTAKES.
 * 
 * @author Paul Codding - paul@hortonworks.com
 * 
 */
public class CTakesExtractor extends EvalFunc<Tuple> {
	public static final String MASTER_FILE_NAME="db.xml";
	public static final String TEMPLATE_STRING="$CTAKES_ROOT$";
	public static final String TEMPLATE_LOOKUP_XML = "/tmp/ctakes-config/sno_rx_16ab-test.xml";
	public static final String MASTER_FOLDER =  "/tmp/ctakes-config/";
	public static final String LOOKUP_FOLDER = "/logs/ctakes-config/";	
	public static final String LOOKUP_XML_PATH = "LOOKUP_XML_PATH";
	public static final String CONFIG_PROPERTIES_PATH = "CONFIG_PROPERTIES_PATH";
	public static final String IS_LOCAL = "IS_LOCAL";
	private static final int MAX_TIMEOUT_MS = 10 * 60 * 1000; // 1 min
	TupleFactory tf = TupleFactory.getInstance();
	BagFactory bf = BagFactory.getInstance();
	long numTuplesProcessed = 0;
	CpeDescription cpeDesc = null;
	Properties myProperties = null;
	private transient RushEndToEndPipeline pipeline = null;

	// String pipelinePath = "";
	/**
	 * Initialize the CpeDescription class.
	 * @throws Exception 
	 */
	File newConfigFolder = null;
	private File createConfigFolderForTask()  {
		try {
			File tempMasterFolder = new File(LOOKUP_FOLDER);
			String randomPrefix = Long.toString(Math.abs((new Random()).nextLong()));
			System.err.println(randomPrefix);
			if(!tempMasterFolder.exists()) {
				FileUtils.forceMkdir(tempMasterFolder);
			}
			newConfigFolder = new File(tempMasterFolder,randomPrefix);
			if(newConfigFolder.exists()) {
				FileUtils.deleteDirectory(newConfigFolder);
			}
			FileUtils.forceMkdir(newConfigFolder);
			FileUtils.copyDirectory(new File(MASTER_FOLDER), newConfigFolder);
			
			
			String fContents = FileUtils.readFileToString(new File(TEMPLATE_LOOKUP_XML));
			File newLookupXml = new File(newConfigFolder,MASTER_FILE_NAME);
			FileUtils.write(newLookupXml,StringUtils.replace(fContents, TEMPLATE_STRING, newConfigFolder.getAbsolutePath()));
			return newLookupXml;	
		}catch(Exception ex) {
			Throwables.propagate(ex);
		}
		return null;
	}
	private void initializeFramework() {
		/*
		if (myProperties == null) {

			myProperties = UDFContext.getUDFContext().getClientSystemProps();
			if (myProperties == null) {
				myProperties = System.getProperties();
			}
			//String path = myProperties.getProperty(LOOKUP_XML_PATH);
			String path = LOOKUP_XML;
			this.pipeline = new RushEndToEndPipeline(LOOKUP_XML);
		}
		*/
		if(this.pipeline==null) {
			File lookupXML = createConfigFolderForTask();
			int failedCount = 0;
			boolean success = false;
			while(!success) {
				try {
					this.pipeline = new RushEndToEndPipeline(lookupXML.getAbsolutePath());
					success=true;
					log.info(" Success after " + failedCount);
				}catch (Exception e) {
					try {
						log.info("Sleeping for 5 seconds" + failedCount + "=" + e.getMessage());
						Thread.currentThread().sleep(5000);
					} catch (InterruptedException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
					failedCount++;
					if(failedCount==10) {
						Throwables.propagate(e);
					}
				}				
			}
		}

	}

	public void finish() {
		if(this.pipeline!=null) {
			//this.initializeFramework();
			this.pipeline.close();
			try {
				FileUtils.deleteDirectory(newConfigFolder);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				Throwables.propagate(e);
			}
			this.pipeline = null;
			
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.pig.EvalFunc#exec(org.apache.pig.data.Tuple)
	 */
	@Override
	public Tuple exec(Tuple input) throws IOException {
		this.initializeFramework();

		long started = System.currentTimeMillis();
		Tuple resultOnly = tf.newTuple(2);
		Tuple result = tf.newTuple(6);
		try {

			String fNameId = input.get(0).toString();
			// Now split it
			int idx = fNameId.lastIndexOf("-");
			String partName = fNameId.substring(idx + 1, fNameId.length());
			String fileName = fNameId.substring(0, idx);
			String encounterId = RushFileUtils.getEncounterId(fileName);
			result.set(0,encounterId );
			result.set(1, partName);
			result.set(2, true);
			String fileContent = (String) input.get(1);
			CTakesResult ctakesResult = this.pipeline.getResult(encounterId, Integer.parseInt(partName), fileContent);
			// inputStr = inputStr.replaceAll("\\r|\\n", "");
			// System.out.println(inputStr);
			// result.set(2, ((String)input.get(1)).replace("\n", " ").replace("\r", " "));
			// inputStr="";
			result.set(3, fileContent);
			result.set(4, ctakesResult.getOutput());
			result.set(5, ctakesResult.getCuis());

		} catch (Exception e) {
			result.set(2, false);
			result.set(4, ExceptionUtils.getStackTrace(e));
			
			log.error(e.getMessage());
			e.printStackTrace();
		}

		return result;
	}


	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.pig.EvalFunc#outputSchema(org.apache.pig.impl.logicalLayer
	 * .schema.Schema)
	 */
	@Override
	public Schema outputSchema(Schema input) {
		try {
			Schema tupleSchema = new Schema();
			tupleSchema.add(new FieldSchema("fname", DataType.CHARARRAY));
			tupleSchema.add(new FieldSchema("part", DataType.CHARARRAY));
			tupleSchema.add(new FieldSchema("parsed", DataType.BOOLEAN));
			tupleSchema.add(new FieldSchema("text", DataType.CHARARRAY));
			// tupleSchema.add(input.getField(0));
			// tupleSchema.add(input.getField(1));
			tupleSchema.add(new FieldSchema("annotations", DataType.CHARARRAY));
			tupleSchema.add(new FieldSchema("cuis", DataType.CHARARRAY));
			return new Schema(new Schema.FieldSchema(null, tupleSchema, DataType.TUPLE));
			// return tupleSchema;
		} catch (Exception e) {
			return null;
		}
	}

	public static void main(String[] args) throws Exception {
		CTakesExtractor p = new CTakesExtractor();
		TupleFactory tf = TupleFactory.getInstance();
		List<String> l = new ArrayList<>();
		l.add("/tmp/cTakesExample/cData/4490.txt-1");
		// l.add("Nasal trauma is an injury to your nose or the areas that surround and
		// support your nose. Internal or external injuries can cause nasal trauma. The
		// position of your nose makes your nasal bones, cartilage, and soft tissue
		// particularly vulnerable to external injuries");

		String s = FileUtils.readFileToString(new File("/tmp/cTakesExample/cData/10380.txt"));
		// System.out.println(s);
		l.add(s);
		Tuple t = tf.newTuple(l);
		Tuple o = p.exec(t);
		//System.out.println(o.get(0) + "\n" + o.get(1) + "\n" + o.get(2) + "\n" + o.get(3));
		System.err.println(o.get(0));
		System.err.println(o.get(1));
		//System.err.println(o.get(2));
		//System.err.println(o.get(3));
		//System.err.println(o.get(4));
		//System.err.println(o.get(5));
		// System.out.println(o.get(2));
		// System.out.println(o.get(3));
		// System.err.println(o.get(1));
		// System.err.println(o.get(2));
		// System.out.println(o.size());
		//FileUtils.writeStringToFile(new File("/tmp/CTAKES_DATA/output/test.xml"), (String) o.get(4));
		p.finish();
	}
}