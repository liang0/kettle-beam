/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2017 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.kettle.beam.perspective;

import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.runners.flink.FlinkPipelineOptions;
import org.apache.beam.runners.flink.FlinkRunner;
import org.apache.beam.runners.spark.SparkPipelineOptions;
import org.apache.beam.runners.spark.SparkRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.PipelineRunner;
import org.apache.beam.sdk.metrics.MetricQueryResults;
import org.apache.beam.sdk.metrics.MetricResult;
import org.apache.beam.sdk.metrics.MetricResults;
import org.apache.beam.sdk.metrics.MetricsFilter;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.MessageBox;
import org.kettle.beam.metastore.BeamJobConfig;
import org.kettle.beam.metastore.BeamJobConfigDialog;
import org.kettle.beam.metastore.FileDefinition;
import org.kettle.beam.metastore.FileDefinitionDialog;
import org.kettle.beam.metastore.JobParameter;
import org.kettle.beam.metastore.RunnerType;
import org.kettle.beam.pipeline.TransMetaPipelineConverter;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.parameters.UnknownParamException;
import org.pentaho.di.core.plugins.KettleURLClassLoader;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.core.dialog.EnterSelectionDialog;
import org.pentaho.di.ui.core.dialog.ErrorDialog;
import org.pentaho.di.ui.spoon.ISpoonMenuController;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.trans.TransGraph;
import org.pentaho.metastore.persist.MetaStoreFactory;
import org.pentaho.metastore.util.PentahoDefaults;
import org.pentaho.ui.xul.dom.Document;
import org.pentaho.ui.xul.impl.AbstractXulEventHandler;

import java.io.File;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class BeamHelper extends AbstractXulEventHandler implements ISpoonMenuController {
  protected static Class<?> PKG = BeamHelper.class; // for i18n

  private static BeamHelper instance = null;

  private Spoon spoon;

  private BeamHelper() {
    spoon = Spoon.getInstance();
  }

  public static BeamHelper getInstance() {
    if ( instance == null ) {
      instance = new BeamHelper(); ;
      instance.spoon.addSpoonMenuController( instance );
    }
    return instance;
  }

  @Override public void updateMenu( Document doc ) {

  }

  public String getName() {
    return "beamHelper";
  }


  public void runBeam() {

    TransMeta transMeta = spoon.getActiveTransformation();
    if ( transMeta == null ) {
      showMessage( "Sorry", "The Apache Beam implementation works with transformations only." );
      return;
    }

    MetaStoreFactory<BeamJobConfig> factory = new MetaStoreFactory<>( BeamJobConfig.class, spoon.getMetaStore(), PentahoDefaults.NAMESPACE );

    try {
      List<String> elementNames = factory.getElementNames();
      Collections.sort( elementNames );
      String[] names = elementNames.toArray( new String[ 0 ] );

      EnterSelectionDialog selectionDialog = new EnterSelectionDialog( spoon.getShell(), names,
        BaseMessages.getString( PKG, "BeamHelper.SelectBeamJobConfigToRun.Title" ),
        BaseMessages.getString( PKG, "BeamHelper.SelectBeamJobConfigToRun.Message" )
      );
      String choice = selectionDialog.open();
      if ( choice != null ) {

        final BeamJobConfig config = factory.loadElement( choice );

        Runnable runnable = new Runnable() {
          @Override public void run() {

            ClassLoader oldContextClassLoader = Thread.currentThread().getContextClassLoader();
            try {
              try {
                // Explain to various classes in the Beam API (@see org.apache.beam.sdk.io.FileSystems)
                // what the context classloader is.
                // Set it back when we're done here.
                //
                Thread.currentThread().setContextClassLoader( BeamHelper.getInstance().getClass().getClassLoader() );

                final Pipeline pipeline = getPipeline( transMeta, config );

                PipelineResult pipelineResult = pipeline.run();

                Timer timer = new Timer();
                TimerTask timerTask = new TimerTask() {
                  @Override public void run() {
                    logMetrics( pipelineResult );
                  }
                };
                // Every 10 seconds
                timer.schedule( timerTask, 10000, 10000 );

                // Wait until we're done
                pipelineResult.waitUntilFinish();

                timer.cancel();
                timer.purge();

                // Log the metrics at the end.
                logMetrics( pipelineResult );

                spoon.getLog().logBasic( "  ----------------- End of Beam job " + pipeline.getOptions().getJobName() + " -----------------------" );
              } finally {
                Thread.currentThread().setContextClassLoader( oldContextClassLoader );
              }

            } catch ( Exception e ) {
              spoon.getDisplay().asyncExec( new Runnable() {
                @Override public void run() {
                  new ErrorDialog( spoon.getShell(), "Error", "There was an error building or executing the pipeline", e );
                }
              } );
            }
          }
        };

        // Create a new thread on the class loader
        //
        Thread thread = new Thread(runnable);
        thread.start();

        showMessage( "Transformation started",
          "Your transformation was started with the selected Beam Runner." + Const.CR +
            "Now check the spoon console logging for execution feedback and metrics on the various steps." + Const.CR +
            "Not all steps are supported, check the project READ.me and Wiki for up-to-date information" + Const.CR + Const.CR +
            "Enjoy Kettle!"
        );

        TransGraph transGraph = spoon.getActiveTransGraph();
        if ( !transGraph.isExecutionResultsPaneVisible() ) {
          transGraph.showExecutionResults();
          CTabItem transLogTab = transGraph.transLogDelegate.getTransLogTab();
          transLogTab.getParent().setSelection( transLogTab );
        }


      }
    } catch ( Exception e ) {
      new ErrorDialog( spoon.getShell(),
        BaseMessages.getString( PKG, "BeamHelper.ErrorRunningTransOnBeam.Title" ),
        BaseMessages.getString( PKG, "BeamHelper.ErrorRunningTransOnBeam.Message" ),
        e
      );
    }

  }

  private KettleURLClassLoader buildKettleURLClassLoader( BeamJobConfig config ) throws Exception {
    // We don't need all the plugins to run locally I think
    //
    List<String> jarFilenames = findLibraryFilesToStage( null, config.getPluginsToStage(), true );
    URL[] urls = new URL[ jarFilenames.size() ];
    int index=0;
    for ( String jarFilename : jarFilenames ) {
      urls[index++] = new File(jarFilename).toURI().toURL();
    }
    return new KettleURLClassLoader( urls, ClassLoader.getSystemClassLoader(), "Beam Kettle Classloader for '"+config.getName()+"'");
  }

  private void configureStandardOptions( BeamJobConfig config, String transformationName, PipelineOptions pipelineOptions ) {
    if ( StringUtils.isNotEmpty( transformationName ) ) {
      String sanitizedName = transformationName.replace( " ", "_" );
      pipelineOptions.setJobName( sanitizedName );
    }
    if ( StringUtils.isNotEmpty( config.getUserAgent() ) ) {
      pipelineOptions.setUserAgent( config.getUserAgent() );
    }
    if ( StringUtils.isNotEmpty( config.getTempLocation() ) ) {
      pipelineOptions.setTempLocation( config.getTempLocation() );
    }
  }


  public Pipeline getPipeline( TransMeta transMeta, BeamJobConfig config ) throws KettleException {

    try {

      if ( StringUtils.isEmpty( config.getRunnerTypeName() ) ) {
        throw new KettleException( "You need to specify a runner type, one of : " + RunnerType.values().toString() );
      }
      PipelineOptions pipelineOptions = null;
      Class<? extends PipelineRunner<?>> pipelineRunnerClass = null;

      RunnerType runnerType = RunnerType.getRunnerTypeByName( transMeta.environmentSubstitute( config.getRunnerTypeName() ) );
      switch ( runnerType ) {
        case Direct:
          pipelineOptions = PipelineOptionsFactory.create();
          pipelineRunnerClass = DirectRunner.class;
          break;
        case DataFlow:
          DataflowPipelineOptions dfOptions = PipelineOptionsFactory.as( DataflowPipelineOptions.class );
          configureDataFlowOptions( config, dfOptions );
          pipelineOptions = dfOptions;
          pipelineRunnerClass = DataflowRunner.class;
          break;
        case Spark:
          SparkPipelineOptions sparkOptions = PipelineOptionsFactory.as( SparkPipelineOptions.class );
          configureSparkOptions( config, sparkOptions );
          pipelineOptions = sparkOptions;
          pipelineRunnerClass = SparkRunner.class;
          showMessage( "Spark", "Still missing a lot of options for Spark, this will probably fail" );
          break;
        case Flink:
          FlinkPipelineOptions flinkOptions = PipelineOptionsFactory.as( FlinkPipelineOptions.class );
          configureFlinkOptions( config, flinkOptions );
          pipelineOptions = flinkOptions;
          pipelineRunnerClass = FlinkRunner.class;
          showMessage( "Flink", "Still missing a lot of options for Flink, this will probably fail" );
          break;
        default:
          throw new KettleException( "Sorry, this isn't implemented yet" );
      }

      configureStandardOptions( config, transMeta.getName(), pipelineOptions );


      setVariablesInTransformation( config, transMeta );

      TransMetaPipelineConverter converter = new TransMetaPipelineConverter( transMeta, spoon.getMetaStore(), config.getPluginsToStage() );
      Pipeline pipeline = converter.createPipeline( pipelineRunnerClass, pipelineOptions );

      return pipeline;
    } catch ( Exception e ) {
      throw new KettleException( "Error configuring local Beam Engine", e );
    }

  }

  private void setVariablesInTransformation( BeamJobConfig config, TransMeta transMeta ) {
    String[] parameters = transMeta.listParameters();
    for ( JobParameter parameter : config.getParameters() ) {
      if ( StringUtils.isNotEmpty( parameter.getVariable() ) ) {
        if ( Const.indexOfString( parameter.getVariable(), parameters ) >= 0 ) {
          try {
            transMeta.setParameterValue( parameter.getVariable(), parameter.getValue() );
          } catch ( UnknownParamException e ) {
            transMeta.setVariable( parameter.getVariable(), parameter.getValue() );
          }
        } else {
          transMeta.setVariable( parameter.getVariable(), parameter.getValue() );
        }
      }
    }
    transMeta.activateParameters();
  }

  private void configureDataFlowOptions( BeamJobConfig config, DataflowPipelineOptions options ) {

    options.setFilesToStage( findLibraryFilesToStage( config.getPluginsToStage() ) );
    options.setProject( config.getGcpProjectId() );
    options.setAppName( config.getGcpAppName() );
    options.setStagingLocation( config.getGcpStagingLocation() );
  }

  private void configureSparkOptions( BeamJobConfig config, SparkPipelineOptions options ) {

    // TODO: Do the other things as well
    options.setFilesToStage( findLibraryFilesToStage() );

  }

  private void configureFlinkOptions( BeamJobConfig config, FlinkPipelineOptions options ) {

    // TODO: Do the other things as well
    options.setFilesToStage( findLibraryFilesToStage() );

  }

  public static List<String> findLibraryFilesToStage() {
    return findLibraryFilesToStage( null, null );
  }

  public static List<String> findLibraryFilesToStage( String pluginFolders ) {
    return findLibraryFilesToStage( null, pluginFolders );
  }

  public static List<String> findLibraryFilesToStage( String baseFolder, String pluginFolders) {
    return findLibraryFilesToStage( baseFolder, pluginFolders, true, true );
  }


  public static List<String> findLibraryFilesToStage( String baseFolder, String pluginFolders, boolean includeParent) {
    return findLibraryFilesToStage( baseFolder, pluginFolders, includeParent, true );
  }

  public static List<String> findLibraryFilesToStage( String baseFolder, String pluginFolders, boolean includeParent, boolean includeBeam ) {

    File base;
    if ( baseFolder == null ) {
      base = new File( "." );
    } else {
      base = new File( baseFolder );
    }

    // Add all the jar files in lib/ to the classpath.
    // Later we'll add the plugins as well...
    //
    Set<String> uniqueNames = new HashSet<>();
    List<String> libraries = new ArrayList<>();

    if (includeParent) {

      File libFolder = new File( base.toString() + "/lib" );

      Collection<File> files = FileUtils.listFiles( libFolder, new String[] { "jar" }, true );
      if ( files != null ) {
        for ( File file : files ) {
          String shortName = file.getName();
          if ( !uniqueNames.contains( shortName ) ) {
            uniqueNames.add( shortName );
            libraries.add( file.getAbsolutePath() );
            // System.out.println( "Adding library : " + file.getAbsolutePath() );
          }
        }
      }
    }

    // A unique list of plugin folders
    //
    Set<String> pluginFoldersSet = new HashSet<>();
    if ( StringUtils.isNotEmpty( pluginFolders ) ) {
      String[] folders = pluginFolders.split( "," );
      for ( String folder : folders ) {
        pluginFoldersSet.add( folder );
      }
    }
    if (includeBeam) {
      // TODO: make this plugin folder configurable
      //
      pluginFoldersSet.add("kettle-beam");
    }

    // Now the selected plugins libs...
    //
    for ( String pluginFolder : pluginFoldersSet ) {
      File pluginsFolder = new File( base.toString() + "/plugins/" + pluginFolder );
      Collection<File> pluginFiles = FileUtils.listFiles( pluginsFolder, new String[] { "jar" }, true );
      if ( pluginFiles != null ) {
        for ( File file : pluginFiles ) {
          String shortName = file.getName();
          if ( !uniqueNames.contains( shortName ) ) {
            uniqueNames.add( shortName );
            libraries.add( file.getAbsolutePath() );
          }
        }
      }
    }

    return libraries;
  }


  private void logMetrics( PipelineResult pipelineResult ) {
    LogChannelInterface log = spoon.getLog();
    MetricResults metricResults = pipelineResult.metrics();

    log.logBasic( "  ----------------- Metrics refresh @ " + new SimpleDateFormat( "yyyy/MM/dd HH:mm:ss" ).format( new Date() ) + " -----------------------" );

    MetricQueryResults allResults = metricResults.queryMetrics( MetricsFilter.builder().build() );
    for ( MetricResult<Long> result : allResults.getCounters() ) {
      log.logBasic( "Name: " + result.getName() + " Attempted: " + result.getAttempted() + " Committed: " + result.getCommitted() );
    }

  }

  public void showMessage( String title, String message ) {

    MessageBox messageBox = new MessageBox( spoon.getShell(), SWT.ICON_INFORMATION | SWT.CLOSE );
    messageBox.setText( title );
    messageBox.setMessage( message );
    messageBox.open();
  }

  public void createFileDefinition() {

    MetaStoreFactory<FileDefinition> factory = new MetaStoreFactory<>( FileDefinition.class, spoon.getMetaStore(), PentahoDefaults.NAMESPACE );

    FileDefinition fileDefinition = new FileDefinition();
    fileDefinition.setName( "My File Definition" );
    boolean ok = false;
    while ( !ok ) {
      FileDefinitionDialog dialog = new FileDefinitionDialog( spoon.getShell(), fileDefinition );
      if ( dialog.open() ) {
        // write to metastore...
        try {
          if ( factory.loadElement( fileDefinition.getName() ) != null ) {
            MessageBox box = new MessageBox( spoon.getShell(), SWT.YES | SWT.NO | SWT.ICON_ERROR );
            box.setText( BaseMessages.getString( PKG, "BeamHelper.Error.FileDefintionExists.Title" ) );
            box.setMessage( BaseMessages.getString( PKG, "BeamHelper.Error.FileDefintionExists.Message" ) );
            int answer = box.open();
            if ( ( answer & SWT.YES ) != 0 ) {
              factory.saveElement( fileDefinition );
              ok = true;
            }
          } else {
            factory.saveElement( fileDefinition );
            ok = true;
          }
        } catch ( Exception exception ) {
          new ErrorDialog( spoon.getShell(),
            BaseMessages.getString( PKG, "BeamHelper.Error.ErrorSavingDefinition.Title" ),
            BaseMessages.getString( PKG, "BeamHelper.Error.ErrorSavingDefinition.Message" ),
            exception );
          return;
        }
      } else {
        // Cancel
        return;
      }
    }
  }

  public void editFileDefinition() {
    MetaStoreFactory<FileDefinition> factory = new MetaStoreFactory<>( FileDefinition.class, spoon.getMetaStore(), PentahoDefaults.NAMESPACE );

    try {
      List<String> elementNames = factory.getElementNames();
      Collections.sort( elementNames );
      String[] names = elementNames.toArray( new String[ 0 ] );

      EnterSelectionDialog selectionDialog = new EnterSelectionDialog( spoon.getShell(), names,
        BaseMessages.getString( PKG, "BeamHelper.SelectDefinitionToEdit.Title" ),
        BaseMessages.getString( PKG, "BeamHelper.SelectDefinitionToEdit.Message" )
      );
      String choice = selectionDialog.open();
      if ( choice != null ) {

        FileDefinition fileDefinition = factory.loadElement( choice );

        FileDefinitionDialog dialog = new FileDefinitionDialog( spoon.getShell(), fileDefinition );
        if ( dialog.open() ) {
          // write to metastore...
          try {
            factory.saveElement( fileDefinition );
          } catch ( Exception exception ) {
            new ErrorDialog( spoon.getShell(),
              BaseMessages.getString( PKG, "BeamHelper.Error.ErrorSavingDefinition.Title" ),
              BaseMessages.getString( PKG, "BeamHelper.Error.ErrorSavingDefinition.Message" ),
              exception );
            return;
          }
        }
      }
    } catch ( Exception e ) {
      new ErrorDialog( spoon.getShell(), "Error", BaseMessages.getString( PKG, "BeamHelper.ErrorEditingDefinition.Message" ), e );
    }
  }

  public void deleteFileDefinition() {
    MetaStoreFactory<FileDefinition> factory = new MetaStoreFactory<>( FileDefinition.class, spoon.getMetaStore(), PentahoDefaults.NAMESPACE );

    try {
      List<String> elementNames = factory.getElementNames();
      Collections.sort( elementNames );
      String[] names = elementNames.toArray( new String[ 0 ] );

      EnterSelectionDialog selectionDialog = new EnterSelectionDialog( spoon.getShell(), names,
        BaseMessages.getString( PKG, "BeamHelper.SelectDefinitionToDelete.Title" ),
        BaseMessages.getString( PKG, "BeamHelper.SelectDefinitionToDelete.Message" )
      );
      String choice = selectionDialog.open();
      if ( choice != null ) {

        MessageBox box = new MessageBox( spoon.getShell(), SWT.YES | SWT.NO | SWT.ICON_ERROR );
        box.setText( BaseMessages.getString( PKG, "BeamHelper.DeleteDefinitionConfirmation.Title" ) );
        box.setMessage( BaseMessages.getString( PKG, "BeamHelper.DeleteDefinitionConfirmation.Message", choice ) );
        int answer = box.open();
        if ( ( answer & SWT.YES ) != 0 ) {
          try {
            factory.deleteElement( choice );
          } catch ( Exception exception ) {
            new ErrorDialog( spoon.getShell(),
              BaseMessages.getString( PKG, "BeamHelper.Error.ErrorDeletingDefinition.Title" ),
              BaseMessages.getString( PKG, "BeamHelper.Error.ErrorDeletingDefinition.Message", choice ),
              exception );
          }
        }
      }
    } catch ( Exception e ) {
      new ErrorDialog( spoon.getShell(), "Error", BaseMessages.getString( PKG, "BeamHelper.Error.ErrorDeletingDefinition.Message" ), e );
    }
  }


  public void createBeamJobConfig() {

    MetaStoreFactory<BeamJobConfig> factory = new MetaStoreFactory<>( BeamJobConfig.class, spoon.getMetaStore(), PentahoDefaults.NAMESPACE );

    BeamJobConfig config = new BeamJobConfig();
    config.setName( "My Beam Job Config" );
    boolean ok = false;
    while ( !ok ) {
      BeamJobConfigDialog dialog = new BeamJobConfigDialog( spoon.getShell(), config );
      if ( dialog.open() ) {
        // write to metastore...
        try {
          if ( factory.loadElement( config.getName() ) != null ) {
            MessageBox box = new MessageBox( spoon.getShell(), SWT.YES | SWT.NO | SWT.ICON_ERROR );
            box.setText( BaseMessages.getString( PKG, "BeamHelper.Error.BeamJobConfigExists.Title" ) );
            box.setMessage( BaseMessages.getString( PKG, "BeamHelper.Error.BeamJobConfigExists.Message" ) );
            int answer = box.open();
            if ( ( answer & SWT.YES ) != 0 ) {
              factory.saveElement( config );
              ok = true;
            }
          } else {
            factory.saveElement( config );
            ok = true;
          }
        } catch ( Exception exception ) {
          new ErrorDialog( spoon.getShell(),
            BaseMessages.getString( PKG, "BeamHelper.Error.ErrorSavingJobConfig.Title" ),
            BaseMessages.getString( PKG, "BeamHelper.Error.ErrorSavingJobConfig.Message" ),
            exception );
          return;
        }
      } else {
        // Cancel
        return;
      }
    }
  }

  public void editBeamJobConfig() {
    MetaStoreFactory<BeamJobConfig> factory = new MetaStoreFactory<>( BeamJobConfig.class, spoon.getMetaStore(), PentahoDefaults.NAMESPACE );

    try {
      List<String> elementNames = factory.getElementNames();
      Collections.sort( elementNames );
      String[] names = elementNames.toArray( new String[ 0 ] );

      EnterSelectionDialog selectionDialog = new EnterSelectionDialog( spoon.getShell(), names,
        BaseMessages.getString( PKG, "BeamHelper.SelectJobConfigToEdit.Title" ),
        BaseMessages.getString( PKG, "BeamHelper.SelectJobConfigToEdit.Message" )
      );
      String choice = selectionDialog.open();
      if ( choice != null ) {

        BeamJobConfig beamJobConfig = factory.loadElement( choice );

        BeamJobConfigDialog dialog = new BeamJobConfigDialog( spoon.getShell(), beamJobConfig );
        if ( dialog.open() ) {
          // write to metastore...
          try {
            factory.saveElement( beamJobConfig );
          } catch ( Exception exception ) {
            new ErrorDialog( spoon.getShell(),
              BaseMessages.getString( PKG, "BeamHelper.Error.ErrorSavingJobConfig.Title" ),
              BaseMessages.getString( PKG, "BeamHelper.Error.ErrorSavingJobConfig.Message" ),
              exception );
            return;
          }
        }
      }
    } catch ( Exception e ) {
      new ErrorDialog( spoon.getShell(), "Error", BaseMessages.getString( PKG, "BeamHelper.ErrorEditingJobConfig.Message" ), e );
    }
  }

  public void deleteBeamJobConfig() {
    MetaStoreFactory<BeamJobConfig> factory = new MetaStoreFactory<>( BeamJobConfig.class, spoon.getMetaStore(), PentahoDefaults.NAMESPACE );

    try {
      List<String> elementNames = factory.getElementNames();
      Collections.sort( elementNames );
      String[] names = elementNames.toArray( new String[ 0 ] );

      EnterSelectionDialog selectionDialog = new EnterSelectionDialog( spoon.getShell(), names,
        BaseMessages.getString( PKG, "BeamHelper.SelectJobConfigToDelete.Title" ),
        BaseMessages.getString( PKG, "BeamHelper.SelectJobConfigToDelete.Message" )
      );
      String choice = selectionDialog.open();
      if ( choice != null ) {

        MessageBox box = new MessageBox( spoon.getShell(), SWT.YES | SWT.NO | SWT.ICON_ERROR );
        box.setText( BaseMessages.getString( PKG, "BeamHelper.DeleteJobConfigConfirmation.Title" ) );
        box.setMessage( BaseMessages.getString( PKG, "BeamHelper.DeleteJobConfigConfirmation.Message", choice ) );
        int answer = box.open();
        if ( ( answer & SWT.YES ) != 0 ) {
          try {
            factory.deleteElement( choice );
          } catch ( Exception exception ) {
            new ErrorDialog( spoon.getShell(),
              BaseMessages.getString( PKG, "BeamHelper.Error.ErrorDeletingJobConfig.Title" ),
              BaseMessages.getString( PKG, "BeamHelper.Error.ErrorDeletingJobConfig.Message", choice ),
              exception );
          }
        }
      }
    } catch ( Exception e ) {
      new ErrorDialog( spoon.getShell(), "Error", BaseMessages.getString( PKG, "BeamHelper.Error.ErrorDeletingJobConfig.Message" ), e );
    }
  }


}
