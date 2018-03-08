package com.ociweb.pronghorn.network;

import java.nio.channels.Selector;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.stage.PronghornStageProcessor;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;
import com.ociweb.pronghorn.util.MemberHolder;
import com.ociweb.pronghorn.util.PoolIdx;
import com.ociweb.pronghorn.util.PoolIdxPredicate;
import com.ociweb.pronghorn.util.ServiceObjectHolder;
import com.ociweb.pronghorn.util.ServiceObjectValidator;

public class ServerCoordinator extends SSLConnectionHolder {
	
	private final static Logger logger = LoggerFactory.getLogger(ServerCoordinator.class);
    
	private ServiceObjectHolder<ServerConnection> socketHolder;
    private Selector                              selectors;
    private MemberHolder                          subscriptions;
    private int[]                                 upgradePipeLookup;
    private ConnectionContext[]                   connectionContext; //NOTE: ObjectArrays would work very well here!!

    
    public final int                                  channelBits;
    public final int                                  channelBitsSize;
    public final int                                  channelBitsMask;

    private final int                                  port;
    private final String                               bindHost;

    public final static int BEGIN_RESPONSE_SHIFT         = 27;
    public final static int INCOMPLETE_RESPONSE_SHIFT    = 28;
    public final static int END_RESPONSE_SHIFT           = 29;//for multi message send this high bit marks the end
    public final static int CLOSE_CONNECTION_SHIFT       = 30;
    public final static int UPGRADE_CONNECTION_SHIFT     = 31;
    
    public final static int UPGRADE_TARGET_PIPE_MASK     = (1<<21)-1; 

    public final static int BEGIN_RESPONSE_MASK          = 1<<BEGIN_RESPONSE_SHIFT;	
    public final static int INCOMPLETE_RESPONSE_MASK     = 1<<INCOMPLETE_RESPONSE_SHIFT;
	public final static int END_RESPONSE_MASK            = 1<<END_RESPONSE_SHIFT;
	public final static int CLOSE_CONNECTION_MASK        = 1<<CLOSE_CONNECTION_SHIFT;
	public final static int UPGRADE_MASK                 = 1<<UPGRADE_CONNECTION_SHIFT;

	private final PoolIdx responsePipeLinePool;
	private final int[] processorLookup;
	private final int moduleParallelism;
	
	public final int maxConcurrentInputs;
	public final int maxConcurrentOutputs;
		
    private final String serviceName;
    private final String defaultPath;
    
	public final boolean requireClientAuth;//clients must send their cert to connect
	
	public static boolean TEST_RECORDS = false;

//	public static long acceptConnectionStart;
//	public static long acceptConnectionRespond;
//	
//	public static long orderSuperStart;
//	public static long newDotRequestStart;

	
	//this is only used for building stages and adding notas
	private PronghornStageProcessor optionalStageProcessor = null;
	
	public ServerCoordinator(TLSCertificates tlsCertificates, String bindHost, int port, 
			                 ServerPipesConfig serverConfig, boolean requireClientAuth){
		this(tlsCertificates,bindHost,port, serverConfig.maxConnectionBitsOnServer,
		   serverConfig.maxConcurrentInputs, serverConfig.maxConcurrentOutputs, 
		   serverConfig.moduleParallelism,
		   requireClientAuth,
		   "Server", "");
	}
	
	public ServerCoordinator(TLSCertificates tlsCertificates, 
			                String bindHost, int port, ServerPipesConfig serverConfig, 
			                String defaultPath, boolean requireClientAuth){
		this(tlsCertificates,bindHost,port, serverConfig.maxConnectionBitsOnServer,
		   serverConfig.maxConcurrentInputs, serverConfig.maxConcurrentOutputs, 
		   serverConfig.moduleParallelism,
		   requireClientAuth,
		   "Server", defaultPath);
	}
	
	public ServerCoordinator(TLSCertificates tlsCertificates, String bindHost, int port,
            int maxConnectionsBits, 
            int maxConcurrentInputs, int maxConcurrentOutputs,
            int moduleParallelism, boolean requireClientAuth){
		
		this(tlsCertificates,bindHost,port,maxConnectionsBits, 
				maxConcurrentInputs, maxConcurrentOutputs,
				moduleParallelism,
				requireClientAuth,
				"Server", "");
	}
	
    public ServerCoordinator(TLSCertificates tlsCertificates,
    		                 String bindHost, int port,
							 int maxConnectionsBits, 
							 int maxConcurrentInputs,
							 int maxConcurrentOutputs,
							 int moduleParallelism,
							 boolean requireClientAuth,
							 String serviceName, 
							 String defaultPath){

		super(tlsCertificates);
		
		this.requireClientAuth = requireClientAuth;
		
        this.port              = port;
        this.channelBits       = maxConnectionsBits;
        this.channelBitsSize   = 1<<channelBits;
        this.channelBitsMask   = channelBitsSize-1;
        this.bindHost          = bindHost;

        this.serviceName       = serviceName;
        this.defaultPath       = defaultPath.startsWith("/") ? defaultPath.substring(1) : defaultPath;
    	this.responsePipeLinePool = new PoolIdx(maxConcurrentInputs, moduleParallelism); 
    	
    	this.maxConcurrentInputs = maxConcurrentInputs;
    	this.maxConcurrentOutputs = maxConcurrentOutputs;

    	this.moduleParallelism = moduleParallelism;
    	
    	////////////////////////////
    	//build lookup table to distribute connections to the same place
    	///////////////////////////
    	int[] distribute = new int[maxConcurrentInputs];
    	int i = maxConcurrentInputs;
    	int j = 0;
    	while (--i>=0) {
    		if (--j<0) {
    			j = moduleParallelism-1;
    		}
    		distribute[i] = j;
    	}    	
    	this.processorLookup = distribute;
        /////////////////////////
    	
    	//logger.info("processorLookup to bind connections to tracks {}",Arrays.toString(processorLookup));
    	
    }
    
    public void setStageNotaProcessor(PronghornStageProcessor p) {
    	optionalStageProcessor = p;
    }
    public void processNota(GraphManager gm, PronghornStage stage) {
    	if (null != optionalStageProcessor) {
    		optionalStageProcessor.process(gm, stage);
    	}
    }
            
    private PronghornStage firstStage;
    
    public void shutdown() {
    	
    	if (null!=firstStage) {
    		firstStage.requestShutdown();
    		firstStage = null;
    	}
   
    	
    //	logger.trace("Server pipe pool:\n {}",responsePipeLinePool);
    	    	
    }

	public void setStart(PronghornStage startStage) {
		this.firstStage = startStage;
	}
	
	
	public void debugResponsePipeLine() {
		logger.info(responsePipeLinePool.toString());
	}
	
	private PipeLineFilter isOk = new PipeLineFilter();
	
	//NOT thread safe only called by ServerSocketReaderStage
	public int responsePipeLineIdx(final long ccId) {
	
		isOk.setId(ccId); //object resuse prevents CG here
		return responsePipeLinePool.get(ccId, isOk);

	}
	
	public int checkForResponsePipeLineIdx(long ccId) {
		return PoolIdx.getIfReserved(responsePipeLinePool,ccId);
	}	
	
	public void releaseResponsePipeLineIdx(long ccId) {		
		responsePipeLinePool.release(ccId);	
		//logger.info("after release we have {} locks",responsePipeLinePool.locks());
	}
	
	public int resposePoolSize() {
		return responsePipeLinePool.length();
	}
    
	public void setFirstUsage(Runnable run) {
		responsePipeLinePool.setFirstUsageCallback(run);
	}
	
	public void setLastUsage(Runnable run) {
		responsePipeLinePool.setNoLocksCallback(run);
	}

	@Override
	public SSLConnection connectionForSessionId(long id) {
		return socketHolder.get(id);		
	}

    public int port() {
    	return port;
    }
    
    public String host() {
    	return bindHost;
    }
    
    
    public static ServiceObjectHolder<ServerConnection> newSocketChannelHolder(ServerCoordinator that) {
        that.connectionContext = new ConnectionContext[that.channelBitsSize];
        //must also create these long lived instances, this would be a good use case for StructuredArray and ObjectLayout
        int i = that.channelBitsSize;
        while (--i >= 0) {
            that.connectionContext[i] = new ConnectionContext();
        }
        
        that.upgradePipeLookup = new int[that.channelBitsSize];
        Arrays.fill(that.upgradePipeLookup, -1);//if not upgraded it remains -1
        
        return that.socketHolder = new ServiceObjectHolder<ServerConnection>(that.channelBits, ServerConnection.class, new SocketValidator(), false/*Do not grow*/);
        
    }
    
    public static ServiceObjectHolder<ServerConnection> getSocketChannelHolder(ServerCoordinator that) {
        return that.socketHolder;
    }
    
    public MemberHolder newMemberHolder() {
        return subscriptions = new MemberHolder(100);
    }
    
    private final class PipeLineFilter implements PoolIdxPredicate {
		
    	private int idx;
		private int validValue;

		private PipeLineFilter() {
			
		}

		public void setId(long ccId) {
			assert(maxConcurrentInputs == processorLookup.length);
				
			this.idx = ((int)ccId)%maxConcurrentInputs;	
			
			this.validValue = processorLookup[idx];
			
			//logger.info("PipeLineFilter set ccId {} idx {} validValue {}", ccId, idx, validValue);
			
		}

		@Override
		public boolean isOk(final int i) {
			return validValue == processorLookup[i]; 
		}
	}
    
    public int moduleParallelism() {
    	return moduleParallelism;
    }

	public static class SocketValidator implements ServiceObjectValidator<ServerConnection> {

        @Override
        public boolean isValid(ServerConnection serviceObject) { 
        	
            return serviceObject.socketChannel.isConnectionPending() || 
                   serviceObject.socketChannel.isConnected();
        }

        @Override
        public void dispose(ServerConnection t) {

        		if (t.socketChannel.isOpen()) {
                    t.close();
                }
        }
        
    }

//	public static AtomicInteger inServerCount = new AtomicInteger(0);
//    public static long start;
    
	@Deprecated
    public static int[] getUpgradedPipeLookupArray(ServerCoordinator that) {
        return that.upgradePipeLookup;
    }
    
    public static void setUpgradePipe(ServerCoordinator that, long channelId, int targetPipe) {
    	that.upgradePipeLookup[(int)(that.channelBitsMask & channelId)] = targetPipe;
    }
    
	public static boolean isWebSocketUpgraded(ServerCoordinator that, long channelId) {
		return that.upgradePipeLookup[(int)(that.channelBitsMask & channelId)]>=0;
	}
	public static int getWebSocketPipeIdx(ServerCoordinator that, long channelId) {
		return that.upgradePipeLookup[(int)(that.channelBitsMask & channelId)];
	}
	
	
    
    public static Selector getSelector(ServerCoordinator that) {
        return that.selectors;
    }
    
    public static ConnectionContext selectorKeyContext(ServerCoordinator that, long channelId) {
        ConnectionContext context = that.connectionContext[(int)(that.channelBitsMask & channelId)];
        context.setChannelId(channelId);        
        return context;
    }


    public void registerSelector(Selector selector) {
    	assert(null==selectors) : "Should not already have a value";
        selectors = selector;
    }

	public String serviceName() {
		return serviceName;
	}
	
	public String defaultPath() {
		return defaultPath;
	}



}
