package org.kettle.beam.pipeline.handler;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.values.PCollection;
import org.kettle.beam.core.KettleRow;
import org.kettle.beam.core.transform.BeamKafkaOutputTransform;
import org.kettle.beam.core.util.JsonRowMeta;
import org.kettle.beam.metastore.BeamJobConfig;
import org.kettle.beam.steps.kafka.BeamProduceMeta;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.metastore.api.IMetaStore;

import java.util.List;
import java.util.Map;

public class BeamKafkaOutputStepHandler extends BeamBaseStepHandler implements BeamStepHandler {

  public BeamKafkaOutputStepHandler( BeamJobConfig beamJobConfig, IMetaStore metaStore, TransMeta transMeta, List<String> stepPluginClasses, List<String> xpPluginClasses ) {
    super( beamJobConfig, false, true, metaStore, transMeta, stepPluginClasses, xpPluginClasses );
  }

  @Override public void handleStep( LogChannelInterface log, StepMeta beamOutputStepMeta, Map<String, PCollection<KettleRow>> stepCollectionMap,
                                    Pipeline pipeline, RowMetaInterface rowMeta, List<StepMeta> previousSteps,
                                    PCollection<KettleRow> input ) throws KettleException {

    BeamProduceMeta beamProduceMeta = (BeamProduceMeta) beamOutputStepMeta.getStepMetaInterface();

    BeamKafkaOutputTransform beamOutputTransform = new BeamKafkaOutputTransform(
      beamOutputStepMeta.getName(),
      transMeta.environmentSubstitute( beamProduceMeta.getBootstrapServers() ),
      transMeta.environmentSubstitute( beamProduceMeta.getTopic() ),
      transMeta.environmentSubstitute( beamProduceMeta.getKeyField() ),
      transMeta.environmentSubstitute( beamProduceMeta.getMessageField() ),
      JsonRowMeta.toJson( rowMeta ),
      stepPluginClasses,
      xpPluginClasses
    );

    // Which step do we apply this transform to?
    // Ignore info hops until we figure that out.
    //
    if ( previousSteps.size() > 1 ) {
      throw new KettleException( "Combining data from multiple steps is not supported yet!" );
    }
    StepMeta previousStep = previousSteps.get( 0 );

    // No need to store this, it's PDone.
    //
    input.apply( beamOutputTransform );
    log.logBasic( "Handled step (KAFKA OUTPUT) : " + beamOutputStepMeta.getName() + ", gets data from " + previousStep.getName() );
  }
}
