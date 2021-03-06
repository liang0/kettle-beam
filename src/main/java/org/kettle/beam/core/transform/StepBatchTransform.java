package org.kettle.beam.core.transform;

import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.commons.lang.StringUtils;
import org.joda.time.Instant;
import org.kettle.beam.core.BeamKettle;
import org.kettle.beam.core.KettleRow;
import org.kettle.beam.core.metastore.SerializableMetaStore;
import org.kettle.beam.core.shared.VariableValue;
import org.kettle.beam.core.util.JsonRowMeta;
import org.kettle.beam.core.util.KettleBeamUtil;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.RowProducer;
import org.pentaho.di.trans.SingleThreadedTransExecutor;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowAdapter;
import org.pentaho.di.trans.step.RowListener;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaDataCombi;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;
import org.pentaho.di.trans.steps.injector.InjectorMeta;
import org.pentaho.metastore.api.IMetaStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public class StepBatchTransform extends StepTransform {

  public StepBatchTransform() {
    super();
  }

  public StepBatchTransform( List<VariableValue> variableValues, String metastoreJson, List<String> stepPluginClasses, List<String> xpPluginClasses,
                             int batchSize, int flushIntervalMs, String stepname, String stepPluginId, String stepMetaInterfaceXml, String inputRowMetaJson, boolean inputStep,
                             List<String> targetSteps, List<String> infoSteps, List<String> infoRowMetaJsons, List<PCollectionView<List<KettleRow>>> infoCollectionViews ) {
    super(variableValues, metastoreJson, stepPluginClasses, xpPluginClasses, batchSize, flushIntervalMs, stepname, stepPluginId,
      stepMetaInterfaceXml, inputRowMetaJson, inputStep, targetSteps, infoSteps, infoRowMetaJsons, infoCollectionViews);
  }

  @Override public PCollectionTuple expand( PCollection<KettleRow> input ) {
    try {
      // Only initialize once on this node/vm
      //
      BeamKettle.init( stepPluginClasses, xpPluginClasses );

      // Similar for the output : treate a TupleTag list for the target steps...
      //
      TupleTag<KettleRow> mainOutputTupleTag = new TupleTag<KettleRow>( KettleBeamUtil.createMainOutputTupleId( stepname ) ) {
      };
      List<TupleTag<KettleRow>> targetTupleTags = new ArrayList<>();
      TupleTagList targetTupleTagList = null;
      for ( String targetStep : targetSteps ) {
        String tupleId = KettleBeamUtil.createTargetTupleId( stepname, targetStep );
        TupleTag<KettleRow> tupleTag = new TupleTag<KettleRow>( tupleId ) {
        };
        targetTupleTags.add( tupleTag );
        if ( targetTupleTagList == null ) {
          targetTupleTagList = TupleTagList.of( tupleTag );
        } else {
          targetTupleTagList = targetTupleTagList.and( tupleTag );
        }
      }
      if ( targetTupleTagList == null ) {
        targetTupleTagList = TupleTagList.empty();
      }

      // Create a new step function, initializes the step
      //
      StepBatchFn stepBatchFn = new StepBatchFn( variableValues, metastoreJson, stepPluginClasses, xpPluginClasses,
        stepname, stepPluginId, stepMetaInterfaceXml, inputRowMetaJson, inputStep,
        targetSteps, infoSteps, infoRowMetaJsons );

      // The actual step functionality
      //
      ParDo.SingleOutput<KettleRow, KettleRow> parDoStepFn = ParDo.of( stepBatchFn );

      // Add optional side inputs...
      //
      if ( infoCollectionViews.size() > 0 ) {
        parDoStepFn = parDoStepFn.withSideInputs( infoCollectionViews );
      }

      // Specify the main output and targeted outputs
      //
      ParDo.MultiOutput<KettleRow, KettleRow> multiOutput = parDoStepFn.withOutputTags( mainOutputTupleTag, targetTupleTagList );

      // Apply the multi output parallel do step function to the main input stream
      //
      PCollectionTuple collectionTuple = input.apply( multiOutput );

      // In the tuple is everything we need to find.
      // Just make sure to retrieve the PCollections using the correct Tuple ID
      // Use KettleBeamUtil.createTargetTupleId()... to make sure
      //
      return collectionTuple;
    } catch ( Exception e ) {
      numErrors.inc();
      LOG.error( "Error transforming data in step '" + stepname + "'", e );
      throw new RuntimeException( "Error transforming data in step", e );
    }

  }

  private class StepBatchFn extends DoFn<KettleRow, KettleRow> {

    private static final long serialVersionUID = 95700000000000002L;

    public static final String INJECTOR_STEP_NAME = "_INJECTOR_";

    protected List<VariableValue> variableValues;
    protected String metastoreJson;
    protected List<String> stepPluginClasses;
    protected List<String> xpPluginClasses;
    protected String stepname;
    protected String stepPluginId;
    protected String stepMetaInterfaceXml;
    protected String inputRowMetaJson;
    protected List<String> targetSteps;
    protected List<String> infoSteps;
    protected List<String> infoRowMetaJsons;
    protected boolean inputStep;
    protected boolean initialize;

    protected List<PCollection<KettleRow>> infoCollections;

    // Log and count parse errors.
    private final Counter numErrors = Metrics.counter( "main", "StepProcessErrors" );

    private transient TransMeta transMeta;
    private transient StepMeta stepMeta;
    private transient RowMetaInterface inputRowMeta;
    private transient RowMetaInterface outputRowMeta;
    private transient List<StepMetaDataCombi> stepCombis;
    private transient Trans trans;
    private transient RowProducer rowProducer;
    private transient RowListener rowListener;
    private transient List<Object[]> resultRows;
    private transient List<List<Object[]>> targetResultRowsList;
    private transient List<RowMetaInterface> targetRowMetas;
    private transient List<RowMetaInterface> infoRowMetas;
    private transient List<RowProducer> infoRowProducers;

    private transient TupleTag<KettleRow> mainTupleTag;
    private transient List<TupleTag<KettleRow>> tupleTagList;

    private transient Counter initCounter;
    private transient Counter readCounter;
    private transient Counter writtenCounter;
    private transient Counter flushBufferCounter;

    private transient SingleThreadedTransExecutor executor;

    private transient Queue<KettleRow> rowBuffer;
    private transient BoundedWindow batchWindow;

    private transient AtomicLong lastTimerCheck;
    private transient Timer timer;

    public StepBatchFn() {
    }


    // I created a private class because instances of this one need access to infoCollectionViews
    //

    public StepBatchFn( List<VariableValue> variableValues, String metastoreJson, List<String> stepPluginClasses, List<String> xpPluginClasses, String stepname, String stepPluginId,
                        String stepMetaInterfaceXml, String inputRowMetaJson, boolean inputStep,
                        List<String> targetSteps, List<String> infoSteps, List<String> infoRowMetaJsons ) {
      this();
      this.variableValues = variableValues;
      this.metastoreJson = metastoreJson;
      this.stepPluginClasses = stepPluginClasses;
      this.xpPluginClasses = xpPluginClasses;
      this.stepname = stepname;
      this.stepPluginId = stepPluginId;
      this.stepMetaInterfaceXml = stepMetaInterfaceXml;
      this.inputRowMetaJson = inputRowMetaJson;
      this.inputStep = inputStep;
      this.targetSteps = targetSteps;
      this.infoSteps = infoSteps;
      this.infoRowMetaJsons = infoRowMetaJsons;
      this.initialize = true;
    }

    /**
     * Reset the row buffer every time we start a new bundle to prevent the output of double rows
     *
     * @param startBundleContext
     */
    @StartBundle
    public void startBundle( StartBundleContext startBundleContext ) {
      Metrics.counter( "startBundle", stepname ).inc();
      if ( "ScriptValueMod".equals( stepPluginId ) && trans != null ) {
        initialize = true;
      }
    }

    @Setup
    public void setup() {
      try {
        rowBuffer = new ConcurrentLinkedQueue();
      } catch ( Exception e ) {
        numErrors.inc();
        LOG.info( "Step '" + stepname + "' : setup error :" + e.getMessage() );
        throw new RuntimeException( "Unable to set up step " + stepname, e );
      }
    }

    @Teardown
    public void tearDown() {
      if ( timer != null ) {
        timer.cancel();
      }
    }

    @ProcessElement
    public void processElement( ProcessContext context, BoundedWindow window ) {

      try {

        if ( initialize ) {
          initialize = false;

          // Initialize Kettle and load extra plugins as well
          //
          BeamKettle.init( stepPluginClasses, xpPluginClasses );

          // The content of the metastore is JSON serialized and inflated below.
          //
          IMetaStore metaStore = new SerializableMetaStore( metastoreJson );

          // Create a very simple new transformation to run single threaded...
          // Single threaded...
          //
          transMeta = new TransMeta();
          transMeta.setMetaStore( metaStore );

          // When the first row ends up in the buffer we start the timer.
          // If the rows are flushed out we reset back to -1;
          //
          lastTimerCheck = new AtomicLong( -1L );

          // Give steps variables from above
          //
          for ( VariableValue variableValue : variableValues ) {
            if ( StringUtils.isNotEmpty( variableValue.getVariable() ) ) {
              transMeta.setVariable( variableValue.getVariable(), variableValue.getValue() );
            }
          }

          // Input row metadata...
          //
          inputRowMeta = JsonRowMeta.fromJson( inputRowMetaJson );
          infoRowMetas = new ArrayList<>();
          for ( String infoRowMetaJson : infoRowMetaJsons ) {
            RowMetaInterface infoRowMeta = JsonRowMeta.fromJson( infoRowMetaJson );
            infoRowMetas.add( infoRowMeta );
          }

          // Create an Injector step with the right row layout...
          // This will help all steps see the row layout statically...
          //
          StepMeta mainInjectorStepMeta = null;
          if ( !inputStep ) {
            mainInjectorStepMeta = createInjectorStep( transMeta, INJECTOR_STEP_NAME, inputRowMeta, 200, 200 );
          }

          // Our main step writes to a bunch of targets
          // Add a dummy step for each one so the step can target them
          //
          int targetLocationY = 200;
          List<StepMeta> targetStepMetas = new ArrayList<>();
          for ( String targetStep : targetSteps ) {
            DummyTransMeta dummyMeta = new DummyTransMeta();
            StepMeta targetStepMeta = new StepMeta( targetStep, dummyMeta );
            targetStepMeta.setLocation( 600, targetLocationY );
            targetStepMeta.setDraw( true );
            targetLocationY += 150;

            targetStepMetas.add( targetStepMeta );
            transMeta.addStep( targetStepMeta );
          }

          // The step might read information from info steps
          // Steps like "Stream Lookup" or "Validator"
          // They read all the data on input from a side input
          //
          List<List<KettleRow>> infoDataSets = new ArrayList<>();
          List<StepMeta> infoStepMetas = new ArrayList<>();
          for ( int i = 0; i < infoSteps.size(); i++ ) {
            String infoStep = infoSteps.get( i );
            PCollectionView<List<KettleRow>> cv = infoCollectionViews.get( i );

            // Get the data from the side input, from the info step(s)
            //
            List<KettleRow> infoDataSet = context.sideInput( cv );
            infoDataSets.add( infoDataSet );

            RowMetaInterface infoRowMeta = infoRowMetas.get( i );

            // Add an Injector step for every info step so the step can read from it
            //
            StepMeta infoStepMeta = createInjectorStep( transMeta, infoStep, infoRowMeta, 200, 350 + 150 * i );
            infoStepMetas.add( infoStepMeta );
          }

          stepCombis = new ArrayList<>();

          // The main step inflated from XML metadata...
          //
          PluginRegistry registry = PluginRegistry.getInstance();
          StepMetaInterface stepMetaInterface = registry.loadClass( StepPluginType.class, stepPluginId, StepMetaInterface.class );
          if ( stepMetaInterface == null ) {
            throw new KettleException( "Unable to load step plugin with ID " + stepPluginId + ", this plugin isn't in the plugin registry or classpath" );
          }

          KettleBeamUtil.loadStepMetadataFromXml( stepname, stepMetaInterface, stepMetaInterfaceXml, transMeta.getMetaStore() );

          stepMeta = new StepMeta( stepname, stepMetaInterface );
          stepMeta.setStepID( stepPluginId );
          stepMeta.setLocation( 400, 200 );
          stepMeta.setDraw( true );
          transMeta.addStep( stepMeta );
          if ( !inputStep ) {
            transMeta.addTransHop( new TransHopMeta( mainInjectorStepMeta, stepMeta ) );
          }
          // The target hops as well
          //
          for ( StepMeta targetStepMeta : targetStepMetas ) {
            transMeta.addTransHop( new TransHopMeta( stepMeta, targetStepMeta ) );
          }

          // And the info hops...
          //
          for ( StepMeta infoStepMeta : infoStepMetas ) {
            transMeta.addTransHop( new TransHopMeta( infoStepMeta, stepMeta ) );
          }

          stepMetaInterface.searchInfoAndTargetSteps( transMeta.getSteps() );

          // This one is single threaded folks
          //
          transMeta.setTransformationType( TransMeta.TransformationType.SingleThreaded );

          // Create the transformation...
          //
          trans = new Trans( transMeta );
          trans.setLogLevel( LogLevel.ERROR );
          trans.setMetaStore( transMeta.getMetaStore() );
          trans.prepareExecution( null );

          // Create producers so we can efficiently pass data
          //
          rowProducer = null;
          if ( !inputStep ) {
            rowProducer = trans.addRowProducer( INJECTOR_STEP_NAME, 0 );
          }
          infoRowProducers = new ArrayList<>();
          for ( String infoStep : infoSteps ) {
            RowProducer infoRowProducer = trans.addRowProducer( infoStep, 0 );
            infoRowProducers.add( infoRowProducer );
          }

          // Find the right interfaces for execution later...
          //
          if ( !inputStep ) {
            StepMetaDataCombi injectorCombi = findCombi( trans, INJECTOR_STEP_NAME );
            stepCombis.add( injectorCombi );
          }

          StepMetaDataCombi stepCombi = findCombi( trans, stepname );
          stepCombis.add( stepCombi );
          outputRowMeta = transMeta.getStepFields( stepname );

          if ( targetSteps.isEmpty() ) {
            rowListener = new RowAdapter() {
              @Override public void rowWrittenEvent( RowMetaInterface rowMeta, Object[] row ) throws KettleStepException {
                resultRows.add( row );
              }
            };
            stepCombi.step.addRowListener( rowListener );
          }

          // Create a list of TupleTag to direct the target rows
          //
          mainTupleTag = new TupleTag<KettleRow>( KettleBeamUtil.createMainOutputTupleId( stepname ) ) {
          };
          tupleTagList = new ArrayList<>();

          // The lists in here will contain all the rows that ended up in the various target steps (if any)
          //
          targetRowMetas = new ArrayList<>();
          targetResultRowsList = new ArrayList<>();

          for ( String targetStep : targetSteps ) {
            StepMetaDataCombi targetCombi = findCombi( trans, targetStep );
            stepCombis.add( targetCombi );
            targetRowMetas.add( transMeta.getStepFields( stepCombi.stepname ) );

            String tupleId = KettleBeamUtil.createTargetTupleId( stepname, targetStep );
            TupleTag<KettleRow> tupleTag = new TupleTag<KettleRow>( tupleId ) {
            };
            tupleTagList.add( tupleTag );
            final List<Object[]> targetResultRows = new ArrayList<>();
            targetResultRowsList.add( targetResultRows );

            targetCombi.step.addRowListener( new RowAdapter() {
              @Override public void rowReadEvent( RowMetaInterface rowMeta, Object[] row ) throws KettleStepException {
                // We send the target row to a specific list...
                //
                targetResultRows.add( row );
              }
            } );
          }

          executor = new SingleThreadedTransExecutor( trans );

          // Initialize the steps...
          //
          executor.init();

          initCounter = Metrics.counter( "init", stepname );
          readCounter = Metrics.counter( "read", stepname );
          writtenCounter = Metrics.counter( "written", stepname );
          flushBufferCounter = Metrics.counter( "flushBuffer", stepname );

          initCounter.inc();

          // Doesn't really start the threads in single threaded mode
          // Just sets some flags all over the place
          //
          trans.startThreads();

          resultRows = new ArrayList<>();

          // Copy the info data sets to the info steps...
          // We do this only once so all subsequent rows can use this.
          //
          for ( int i = 0; i < infoSteps.size(); i++ ) {
            RowProducer infoRowProducer = infoRowProducers.get( i );
            List<KettleRow> infoDataSet = infoDataSets.get( i );
            StepMetaDataCombi combi = findCombi( trans, infoSteps.get( i ) );
            RowMetaInterface infoRowMeta = infoRowMetas.get( i );

            // Pass and process the rows in the info steps
            //
            for ( KettleRow infoRowData : infoDataSet ) {
              infoRowProducer.putRow( infoRowMeta, infoRowData.getRow() );
              combi.step.processRow( combi.meta, combi.data );
            }

            // By calling finished() steps like Stream Lookup know no more rows are going to come
            // and they can start to work with the info data set
            //
            infoRowProducer.finished();

            // Call once more to flag input as done, step as finished.
            //
            combi.step.processRow( combi.meta, combi.data );
          }

          // Install a timer to check every second if the buffer is stale and needs to be flushed...
          //
          if ( flushIntervalMs > 0 ) {
            TimerTask timerTask = new TimerTask() {
              @Override public void run() {
                // Check on the state of the buffer, flush if needed...
                //
                synchronized ( rowBuffer ) {
                  long difference = System.currentTimeMillis() - lastTimerCheck.get();
                  if ( lastTimerCheck.get()<=0 || difference > flushIntervalMs ) {
                    try {
                      emptyRowBuffer( new StepProcessContext( context ) );
                    } catch ( Exception e ) {
                      throw new RuntimeException( "Unable to flush row buffer when it got stale after " + difference + " ms", e );
                    }
                    lastTimerCheck.set( System.currentTimeMillis() );
                  }
                }
              }
            };
            timer = new Timer( "Flush timer of step " + stepname );
            timer.schedule( timerTask, 100, 100 );
          }
        }

        // Get one row from the context main input and make a copy so we can change it.
        //
        KettleRow originalInputRow = context.element();
        KettleRow inputRow = KettleBeamUtil.copyKettleRow( originalInputRow, inputRowMeta );
        readCounter.inc();

        // Take care of the age of the buffer...
        //
        if ( flushIntervalMs > 0 && rowBuffer.isEmpty() ) {
          lastTimerCheck.set( System.currentTimeMillis() );
        }

        // Add the row to the buffer.
        //
        synchronized ( rowBuffer ) {
          rowBuffer.add( inputRow );
          batchWindow = window;

          synchronized ( rowBuffer ) {
            if ( rowBuffer.size() >= batchSize ) {
              emptyRowBuffer( new StepProcessContext( context ) );
            }
          }
        }
      } catch ( Exception e ) {
        numErrors.inc();
        LOG.info( "Step execution error :" + e.getMessage() );
        throw new RuntimeException( "Error executing StepBatchFn", e );
      }
    }

    @FinishBundle
    public void finishBundle( FinishBundleContext context ) {
      try {
        synchronized ( rowBuffer ) {
          if ( !rowBuffer.isEmpty() ) {
            // System.out.println( "Finishing bundle with " + rowBuffer.size() + " rows in the buffer" );
            emptyRowBuffer( new StepFinishBundleContext( context, batchWindow ) );
          }
        }
      } catch ( Exception e ) {
        numErrors.inc();
        LOG.info( "Step finishing bundle error :" + e.getMessage() );
        throw new RuntimeException( "Error finalizing bundle of step '" + stepname + "'", e );
      }
    }

    private transient int maxInputBufferSize = 0;
    private transient int minInputBufferSize = Integer.MAX_VALUE;

    /**
     * Attempt to empty the row buffer
     *
     * @param context
     * @throws KettleException
     */
    private synchronized void emptyRowBuffer( TupleOutputContext<KettleRow> context ) throws KettleException {
      synchronized ( rowBuffer ) {

        List<KettleRow> buffer = new ArrayList<>();

        // Copy the data to avoid race conditions
        //
        int size = rowBuffer.size();
        for ( int i = 0; i < size; i++ ) {
          KettleRow kettleRow = rowBuffer.poll();
          buffer.add( kettleRow );
        }

        // Only do something if we have work to do
        //
        if ( buffer.isEmpty() ) {
          return;
        }

        if ( !rowBuffer.isEmpty() ) {
          System.err.println( "Async action detected on rowBuffer" );
        }

        // Empty all the row buffers for another iteration
        //
        resultRows.clear();
        for ( int t = 0; t < targetSteps.size(); t++ ) {
          targetResultRowsList.get( t ).clear();
        }

        // Pass the rows in the rowBuffer to the input RowSet
        //
        if ( !inputStep ) {
          int bufferSize = buffer.size();
          if ( maxInputBufferSize < bufferSize ) {
            Metrics.counter( "maxInputSize", stepname ).inc( bufferSize - maxInputBufferSize );
            maxInputBufferSize = bufferSize;
          }
          if ( minInputBufferSize > bufferSize ) {
            if ( minInputBufferSize == Integer.MAX_VALUE ) {
              Metrics.counter( "minInputSize", stepname ).inc( bufferSize );
            } else {
              Metrics.counter( "minInputSize", stepname ).dec( bufferSize - minInputBufferSize );
            }
            minInputBufferSize = bufferSize;
          }

          for ( KettleRow inputRow : buffer ) {
            rowProducer.putRow( inputRowMeta, inputRow.getRow() );
          }
        }

        // Execute all steps in the transformation
        //
        executor.oneIteration();

        // Evaluate the results...
        //

        // Pass all rows in the output to the process context
        //
        for ( Object[] resultRow : resultRows ) {

          // Pass the row to the process context
          //
          context.output( mainTupleTag, new KettleRow( resultRow ) );
          writtenCounter.inc();
        }

        // Pass whatever ended up on the target nodes
        //
        for ( int t = 0; t < targetResultRowsList.size(); t++ ) {
          List<Object[]> targetRowsList = targetResultRowsList.get( t );
          TupleTag<KettleRow> tupleTag = tupleTagList.get( t );

          for ( Object[] targetRow : targetRowsList ) {
            context.output( tupleTag, new KettleRow( targetRow ) );
          }
        }

        flushBufferCounter.inc();
        buffer.clear(); // gc
        lastTimerCheck.set( System.currentTimeMillis() ); // No need to check sooner
      }
    }

    private StepMeta createInjectorStep( TransMeta transMeta, String injectorStepName, RowMetaInterface injectorRowMeta, int x, int y ) {
      InjectorMeta injectorMeta = new InjectorMeta();
      injectorMeta.allocate( injectorRowMeta.size() );
      for ( int i = 0; i < injectorRowMeta.size(); i++ ) {
        ValueMetaInterface valueMeta = injectorRowMeta.getValueMeta( i );
        injectorMeta.getFieldname()[ i ] = valueMeta.getName();
        injectorMeta.getType()[ i ] = valueMeta.getType();
        injectorMeta.getLength()[ i ] = valueMeta.getLength();
        injectorMeta.getPrecision()[ i ] = valueMeta.getPrecision();
      }
      StepMeta injectorStepMeta = new StepMeta( injectorStepName, injectorMeta );
      injectorStepMeta.setLocation( x, y );
      injectorStepMeta.setDraw( true );
      transMeta.addStep( injectorStepMeta );

      return injectorStepMeta;
    }

    private StepMetaDataCombi findCombi( Trans trans, String stepname ) {
      for ( StepMetaDataCombi combi : trans.getSteps() ) {
        if ( combi.stepname.equals( stepname ) ) {
          return combi;
        }
      }
      throw new RuntimeException( "Configuration error, step '" + stepname + "' not found in transformation" );
    }
  }

  private interface TupleOutputContext<T> {
    void output( TupleTag<T> tupleTag, T output );
  }

  private class StepProcessContext implements TupleOutputContext<KettleRow> {

    private DoFn.ProcessContext context;

    public StepProcessContext( DoFn.ProcessContext processContext ) {
      this.context = processContext;
    }

    @Override public void output( TupleTag<KettleRow> tupleTag, KettleRow output ) {
      context.output( tupleTag, output );
    }
  }

  private class StepFinishBundleContext implements TupleOutputContext<KettleRow> {

    private DoFn.FinishBundleContext context;
    private BoundedWindow batchWindow;

    public StepFinishBundleContext( DoFn.FinishBundleContext context, BoundedWindow batchWindow ) {
      this.context = context;
      this.batchWindow = batchWindow;
    }

    @Override public void output( TupleTag<KettleRow> tupleTag, KettleRow output ) {
      context.output( tupleTag, output, Instant.now(), batchWindow );
    }
  }
}
