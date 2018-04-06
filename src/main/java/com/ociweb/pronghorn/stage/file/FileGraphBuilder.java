package com.ociweb.pronghorn.stage.file;

import java.io.File;
import java.io.IOException;

import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.pipe.PipeConfig;
import com.ociweb.pronghorn.pipe.RawDataSchema;
import com.ociweb.pronghorn.stage.encrypt.RawDataCryptAESCBCPKCS5Stage;
import com.ociweb.pronghorn.stage.file.schema.BlockStorageReceiveSchema;
import com.ociweb.pronghorn.stage.file.schema.BlockStorageXmitSchema;
import com.ociweb.pronghorn.stage.file.schema.PersistedBlobLoadConsumerSchema;
import com.ociweb.pronghorn.stage.file.schema.PersistedBlobLoadProducerSchema;
import com.ociweb.pronghorn.stage.file.schema.PersistedBlobLoadReleaseSchema;
import com.ociweb.pronghorn.stage.file.schema.PersistedBlobStoreConsumerSchema;
import com.ociweb.pronghorn.stage.file.schema.PersistedBlobStoreProducerSchema;
import com.ociweb.pronghorn.stage.file.schema.SequentialCtlSchema;
import com.ociweb.pronghorn.stage.file.schema.SequentialRespSchema;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;

public class FileGraphBuilder {

	public static void buildSequentialReplayer(GraphManager gm,
			Pipe<PersistedBlobLoadReleaseSchema>  fromStoreRelease,
			Pipe<PersistedBlobLoadConsumerSchema> fromStoreConsumer,
			Pipe<PersistedBlobLoadProducerSchema> fromStoreProducer,
			Pipe<PersistedBlobStoreConsumerSchema> toStoreConsumer,
			Pipe<PersistedBlobStoreProducerSchema> toStoreProducer,
			short inFlightCount, int largestBlock,
			File targetDirectory, byte[] cypherBlock, long rate,
			String backgroundColor) {
		
		if (cypherBlock != null) {
			if (cypherBlock.length!=16) {
				throw new UnsupportedOperationException("cypherBlock must be 16 bytes");
			}
		}
		
		PipeConfig<SequentialCtlSchema> ctlConfig = SequentialCtlSchema.instance.newPipeConfig(inFlightCount);
		PipeConfig<SequentialRespSchema> respConfig = SequentialRespSchema.instance.newPipeConfig(inFlightCount);
		PipeConfig<RawDataSchema> releaseConfig = RawDataSchema.instance.newPipeConfig(inFlightCount, 128);		
		PipeConfig<RawDataSchema> dataConfig = RawDataSchema.instance.newPipeConfig(inFlightCount, largestBlock);
					
				
		Pipe<SequentialCtlSchema>[] control = new Pipe[]  {
				             new Pipe<SequentialCtlSchema>(ctlConfig),
				             new Pipe<SequentialCtlSchema>(ctlConfig),
				             new Pipe<SequentialCtlSchema>(ctlConfig)};
		
		Pipe<SequentialRespSchema>[] response = new Pipe[] {
				             new Pipe<SequentialRespSchema>(respConfig),
				             new Pipe<SequentialRespSchema>(respConfig),
				             new Pipe<SequentialRespSchema>(respConfig)};
		
		Pipe<RawDataSchema>[] fileDataToLoad = new Pipe[] {
							 new Pipe<RawDataSchema>(dataConfig.grow2x()),
							 new Pipe<RawDataSchema>(dataConfig.grow2x()),
							 new Pipe<RawDataSchema>(releaseConfig.grow2x())};
		
		Pipe<RawDataSchema>[] fileDataToSave = new Pipe[] {
							 new Pipe<RawDataSchema>(dataConfig),
							 new Pipe<RawDataSchema>(dataConfig),
				             new Pipe<RawDataSchema>(releaseConfig)};
		
		String[] paths = null;
		try {
			paths = new String[]{	
					File.createTempFile("seqRep", ".dat0", targetDirectory).getAbsolutePath(),
					File.createTempFile("seqRep", ".dat1", targetDirectory).getAbsolutePath(),
					File.createTempFile("seqRep", ".idx",  targetDirectory).getAbsolutePath()};
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		SequentialFileReadWriteStage readWriteStage = new SequentialFileReadWriteStage(gm, control, response, 
									     fileDataToSave, fileDataToLoad, 
									     paths);
		if (rate>0) {
			GraphManager.addNota(gm, GraphManager.SCHEDULE_RATE, rate, readWriteStage);
		}
		if (null!=backgroundColor) {
			GraphManager.addNota(gm, GraphManager.DOT_BACKGROUND, backgroundColor, readWriteStage);
		}
		if (null != cypherBlock) {
			
			Pipe<RawDataSchema>[] cypherDataToLoad = new Pipe[] {
					 new Pipe<RawDataSchema>(dataConfig),
					 new Pipe<RawDataSchema>(dataConfig),
					 new Pipe<RawDataSchema>(releaseConfig)};
	
			Pipe<RawDataSchema>[] cypherDataToSave = new Pipe[] {
					 new Pipe<RawDataSchema>(dataConfig.grow2x()),
					 new Pipe<RawDataSchema>(dataConfig.grow2x()),
		             new Pipe<RawDataSchema>(releaseConfig.grow2x())};
			
			int i = 3;
			while (--i>=0) {
				
				Pipe<BlockStorageReceiveSchema> doFinalReceive1 = BlockStorageReceiveSchema.instance.newPipe(10, 1000);
				Pipe<BlockStorageXmitSchema> doFinalXmit1 = BlockStorageXmitSchema.instance.newPipe(10, 1000);
					
				Pipe<BlockStorageReceiveSchema> doFinalReceive2 = BlockStorageReceiveSchema.instance.newPipe(10, 1000);
				Pipe<BlockStorageXmitSchema> doFinalXmit2 = BlockStorageXmitSchema.instance.newPipe(10, 1000);
				
				BlockStorageStage.newInstance(gm, paths[i]+".tail", 
						              new Pipe[] {doFinalXmit1, doFinalXmit2},
						              new Pipe[] {doFinalReceive1, doFinalReceive2});
				
				RawDataCryptAESCBCPKCS5Stage crypt1 = new RawDataCryptAESCBCPKCS5Stage(gm, cypherBlock, true, cypherDataToSave[i], fileDataToSave[i],
				                         doFinalReceive1, doFinalXmit1);
				GraphManager.addNota(gm, GraphManager.SCHEDULE_RATE, rate, crypt1);
				GraphManager.addNota(gm, GraphManager.DOT_BACKGROUND, backgroundColor, crypt1);
				
				RawDataCryptAESCBCPKCS5Stage crypt2 = new RawDataCryptAESCBCPKCS5Stage(gm, cypherBlock, false, fileDataToLoad[i], cypherDataToLoad[i],
				                         doFinalReceive2, doFinalXmit2);
				GraphManager.addNota(gm, GraphManager.SCHEDULE_RATE, rate, crypt2);
				GraphManager.addNota(gm, GraphManager.DOT_BACKGROUND, backgroundColor, crypt2);
			}			
			
			SequentialReplayerStage stage = new SequentialReplayerStage(gm, toStoreConsumer, toStoreProducer, fromStoreRelease, fromStoreConsumer, fromStoreProducer, control, response, cypherDataToSave, cypherDataToLoad);
			GraphManager.addNota(gm, GraphManager.SCHEDULE_RATE, rate, stage);
			GraphManager.addNota(gm, GraphManager.DOT_BACKGROUND, backgroundColor, stage);
		} else {
			SequentialReplayerStage stage = new SequentialReplayerStage(gm, toStoreConsumer, toStoreProducer, fromStoreRelease, fromStoreConsumer, fromStoreProducer, control, response, fileDataToSave, fileDataToLoad);
			GraphManager.addNota(gm, GraphManager.SCHEDULE_RATE, rate, stage);
			GraphManager.addNota(gm, GraphManager.DOT_BACKGROUND, backgroundColor, stage);
		}
	}

}
