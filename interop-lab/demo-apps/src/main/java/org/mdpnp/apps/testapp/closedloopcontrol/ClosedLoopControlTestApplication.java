package org.mdpnp.apps.testapp.closedloopcontrol;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;
import java.util.regex.Pattern;

import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFx;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.Device;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.chart.Chart;
import org.mdpnp.apps.testapp.chart.DateAxis;
import org.mdpnp.apps.testapp.vital.Vital;
import org.mdpnp.apps.testapp.vital.VitalModel;
import org.mdpnp.apps.testapp.vital.VitalModelImpl;
import org.mdpnp.apps.testapp.vital.VitalSign;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.devices.PartitionAssignmentController;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSEvent;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSListener;
import org.mdpnp.devices.MDSHandler.Patient.PatientEvent;
import org.mdpnp.devices.MDSHandler.Patient.PatientListener;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.rti.dds.subscription.Subscriber;

import ice.FlowRateObjectiveDataWriter;
import ice.MDSConnectivity;
import ice.Patient;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ListChangeListener.Change;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import javafx.util.Duration;
import javafx.util.StringConverter;
import javafx.util.converter.*;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Toggle;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

public class ClosedLoopControlTestApplication implements EventHandler<ActionEvent> {
	
	private DeviceListModel dlm;
	private NumericFxList numeric;
	private SampleArrayFxList samples;
	private FlowRateObjectiveDataWriter writer;
	private MDSHandler mdsHandler;
	private VitalModel vitalModel;
	
	@FXML VBox bpVBox;
	@FXML VBox bpGraphBox;	//TODO: Something simpler than a VBox?
	private DateAxis dateAxis;
		
	@FXML private ComboBox<Device> bpsources;
	@FXML private ComboBox<Device> pumps;
	@FXML private TextField currentDiastolic;
	@FXML private TextField currentSystolic;
	@FXML private TextField currentMean;
	@FXML private Spinner targetSystolic;
	@FXML private Spinner targetDiastolic;
	@FXML private Spinner systolicAlarm;
	@FXML private Spinner diastolicAlarm;
	@FXML private Label errorLabel;
	@FXML private ToggleGroup operatingMode;
	@FXML private Toggle openRadio;
	@FXML private Spinner infusionRate;
	@FXML private BorderPane main;
	@FXML private Label lastPumpUpdate;
	@FXML private Label lastBPUpdate;
	
	
	private final String FLOW_RATE=rosetta.MDC_FLOW_FLUID_PUMP.VALUE;
	private final String ARTERIAL=rosetta.MDC_PRESS_BLD_ART_ABP.VALUE;
	
	private static final float LOWER_INFUSION_LIMIT=100f;
	private static final float UPPER_INFUSION_LIMIT=2000f;
	private static final int LOWER_SYSTOLIC_LIMIT=40;
	private static final int UPPER_SYSTOLIC_LIMIT=200;
	private static final int LOWER_DIASTOLIC_LIMIT=10;
	private static final int UPPER_DIASTOLIC_LIMIT=150;
	private static final int SYSTOLIC_ALARM_LOWER=0;
	private static final int SYSTOLIC_ALARM_HIGHER=150;
	private static final int DIASTOLIC_ALARM_LOWER=0;
	private static final int DIASTOLIC_ALARM_HIGHER=120;
	private static final int MIN_FLOW_RATE=100;
	private static final int MAX_FLOW_RATE=2000;
	
	private static final long interval= 5 * 60 * 1000L; 
	
	private static final Logger log = LoggerFactory.getLogger(ClosedLoopControlTestApplication.class);
	
	private boolean listenerPresent;
	
	private String[] SYS_PARAMS=new String[] { rosetta.MDC_PRESS_BLD_SYS.VALUE, rosetta.MDC_PRESS_BLD_ART_SYS.VALUE, rosetta.MDC_PRESS_INTRA_CRAN_SYS.VALUE,
            rosetta.MDC_PRESS_BLD_AORT_SYS.VALUE, rosetta.MDC_PRESS_BLD_ART_ABP_SYS.VALUE, rosetta.MDC_PRESS_BLD_ART_FEMORAL_SYS.VALUE,
            rosetta.MDC_PRESS_BLD_ART_PULM_SYS.VALUE, rosetta.MDC_PRESS_BLD_ART_UMB_SYS.VALUE, rosetta.MDC_PRESS_BLD_ATR_LEFT_SYS.VALUE,
            rosetta.MDC_PRESS_BLD_ATR_RIGHT_SYS.VALUE
    };
	
	private String[] DIA_PARAMS=new String[] {
			rosetta.MDC_PRESS_BLD_DIA.VALUE, rosetta.MDC_PRESS_BLD_ART_DIA.VALUE, rosetta.MDC_PRESS_INTRA_CRAN_DIA.VALUE,
            rosetta.MDC_PRESS_BLD_AORT_DIA.VALUE, rosetta.MDC_PRESS_BLD_ART_ABP_DIA.VALUE, rosetta.MDC_PRESS_BLD_ART_FEMORAL_DIA.VALUE,
            rosetta.MDC_PRESS_BLD_ART_PULM_DIA.VALUE, rosetta.MDC_PRESS_BLD_ART_UMB_DIA.VALUE, rosetta.MDC_PRESS_BLD_ATR_LEFT_DIA.VALUE,
            rosetta.MDC_PRESS_BLD_ATR_RIGHT_DIA.VALUE
	};
	
	private String[] MEAN_PARAMS=new String[] {
			rosetta.MDC_PRESS_BLD_MEAN.VALUE, rosetta.MDC_PRESS_BLD_ART_MEAN.VALUE, rosetta.MDC_PRESS_INTRA_CRAN_MEAN.VALUE,
            rosetta.MDC_PRESS_BLD_AORT_MEAN.VALUE, rosetta.MDC_PRESS_BLD_ART_ABP_MEAN.VALUE, rosetta.MDC_PRESS_BLD_ART_FEMORAL_MEAN.VALUE,
            rosetta.MDC_PRESS_BLD_ART_PULM_MEAN.VALUE, rosetta.MDC_PRESS_BLD_ART_UMB_MEAN.VALUE, rosetta.MDC_PRESS_BLD_ATR_LEFT_MEAN.VALUE,
            rosetta.MDC_PRESS_BLD_ATR_RIGHT_MEAN.VALUE
	};
	
	private HashMap<String, Parent> udiToPump=new HashMap<>();
	
	/**
	 * The "current" patient, used to determine if the patient has changed
	 */
	private Patient currentPatient;
	
	private Connection dbconn;
	private PreparedStatement controlStatement;
	
	/**
	 * Graphing timeline used to animate the axis
	 */
	private Timeline timeline;
	
	private static final String JDBC_PROPS_FILE_NAME="icejdbc.properties";
	
	private IntegerProperty systolicProperty=new SimpleIntegerProperty();
	private IntegerProperty diastolicProperty=new SimpleIntegerProperty();
	
	public void set(DeviceListModel dlm, NumericFxList numeric, SampleArrayFxList samples, FlowRateObjectiveDataWriter writer, MDSHandler mdsHandler, VitalModel vitalModel) {
		this.dlm=dlm;
		this.numeric=numeric;
		this.samples=samples;
		this.writer=writer;
		this.mdsHandler=mdsHandler;
		this.vitalModel=vitalModel;
		configureFields();
	}
	
	Pattern p=Pattern.compile("[0-9]?");
	
	private void configureFields() {
		//main.setPrefSize(400, 400);
		//new TextFormatter<?>(UnaryOperator<TextFormatter.Change> change
		targetDiastolic.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(LOWER_DIASTOLIC_LIMIT,UPPER_DIASTOLIC_LIMIT));
		targetDiastolic.setEditable(true);
		targetSystolic.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(LOWER_SYSTOLIC_LIMIT,UPPER_SYSTOLIC_LIMIT));
		targetSystolic.setEditable(true);
		diastolicAlarm.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(DIASTOLIC_ALARM_LOWER, DIASTOLIC_ALARM_HIGHER));
		diastolicAlarm.setEditable(true);
		systolicAlarm.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(SYSTOLIC_ALARM_LOWER, SYSTOLIC_ALARM_HIGHER));
		systolicAlarm.setEditable(true);
		SpinnerValueFactory.DoubleSpinnerValueFactory rateFactory=new SpinnerValueFactory.DoubleSpinnerValueFactory(MIN_FLOW_RATE, MAX_FLOW_RATE);
		rateFactory.setAmountToStepBy(0.1);
		infusionRate.setValueFactory(rateFactory);
		
		/*
		 * We use bindBidirectional here because systolicProperty and diastolicProperty and numbers,
		 * but the text field is a String, and bindBidrectional allows us to specify a converter
		 * to handle the change between the two.
		 */
		currentSystolic.textProperty().bindBidirectional(systolicProperty, new NumberStringConverter());
		currentDiastolic.textProperty().bindBidirectional(diastolicProperty, new NumberStringConverter());
		
		currentSystolic.setEditable(false);
		currentDiastolic.setEditable(false);
		

		operatingMode.selectedToggleProperty().addListener(new ChangeListener<Toggle>() {

			@Override
			public void changed(ObservableValue<? extends Toggle> ov, Toggle oldToggle, Toggle newToggle) {
				if(newToggle.equals(openRadio)) {
					bpVBox.setVisible(false);
					bpVBox.setManaged(false);
				} else {
					bpVBox.setManaged(true);
					bpVBox.setVisible(true);
				}
				
			}
			
		});
		
	}
	
	private void isInt(String s) throws NumberFormatException {
		if(s.length()==0) return;
		Integer.parseInt(s);
	}
	
	public void stop() {
		//TODO: Stop listening to the BP waveform for efficiency?

	}
	
	public void activate() {
		log.info("CLC.activate does nothing at the moment");
	}
	
	class BPDeviceChangeListener implements ChangeListener<Device> {

		@Override
		public void changed(ObservableValue<? extends Device> observable, Device oldValue, Device newValue) {
			handleBPDeviceChange(newValue);
		}
	}

	BPDeviceChangeListener bpDeviceChangeListener=new BPDeviceChangeListener();
	
	public void start(EventLoop eventLoop, Subscriber subscriber) {
		
		//Rely on addition of metrics to add devices...
		numeric.addListener(new ListChangeListener<NumericFx>() {
			@Override
			public void onChanged(Change<? extends NumericFx> change) {
				while(change.next()) {
					change.getAddedSubList().forEach( n -> {
						if(n.getMetric_id().equals(FLOW_RATE)) {
							pumps.getItems().add(dlm.getByUniqueDeviceIdentifier(n.getUnique_device_identifier()));
						}

					});
				}
			}
		});
		
		//...and removal of devices to remove devices.
		dlm.getContents().addListener(new ListChangeListener<Device>() {
			@Override
			public void onChanged(Change<? extends Device> change) {
				while(change.next()) {
					change.getRemoved().forEach( d-> {
						bpsources.getItems().remove(d);
						pumps.getItems().remove(d);
					});
				}
			}
		});
		
		//Similarly, rely on metrics to add BP devices.
		samples.addListener(new ListChangeListener<SampleArrayFx>() {
			@Override
			public void onChanged(Change<? extends SampleArrayFx> change) {
				while(change.next()) {
					change.getAddedSubList().forEach( n -> {
						if(n.getMetric_id().equals(ARTERIAL)) {
							bpsources.getItems().add(dlm.getByUniqueDeviceIdentifier(n.getUnique_device_identifier()));
						}
					});
				}
				
			}
		});
		
		bpsources.getSelectionModel().selectedItemProperty().addListener(bpDeviceChangeListener);
		listenerPresent=true;
		
		bpsources.setCellFactory(new Callback<ListView<Device>,ListCell<Device>>() {

			@Override
			public ListCell<Device> call(ListView<Device> device) {
				return new DeviceListCell();
			}
			
		});
		
		bpsources.setConverter(new StringConverter<Device>() {

			@Override
			public Device fromString(String arg0) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public String toString(Device device) {
				// TODO Auto-generated method stub
				return device.getModel()+"("+device.getComPort()+")";
			}
			
		});
		
		pumps.setCellFactory(new Callback<ListView<Device>,ListCell<Device>>() {

			@Override
			public ListCell<Device> call(ListView<Device> device) {
				return new DeviceListCell();
			}
			
		});
		
		pumps.setConverter(new StringConverter<Device>() {

			@Override
			public Device fromString(String arg0) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			public String toString(Device device) {
				// TODO Auto-generated method stub
				return device.getModel()+"("+device.getComPort()+")";
			}
			
		});
		
		mdsHandler.addPatientListener(new PatientListener() {

			@Override
			public void handlePatientChange(PatientEvent evt) {
				
			}
			
		});
		
		mdsHandler.addConnectivityListener(new MDSListener() {

			@Override
			public void handleConnectivityChange(MDSEvent evt) {
		        ice.MDSConnectivity c = (MDSConnectivity) evt.getSource();

		        String mrnPartition = PartitionAssignmentController.findMRNPartition(c.partition);

		        if(mrnPartition != null) {
		            //log.info("udi " + c.unique_device_identifier + " is MRN=" + mrnPartition);

		            Patient p = new Patient();
		            p.mrn = PartitionAssignmentController.toMRN(mrnPartition);
		            
		            if(currentPatient==null) {
		            	/*
		            	 * The patient has definitely changed - even if the selected patient is "Unassigned",
		            	 * then that "Patient" has an ID
		            	 */
		            	currentPatient=p;
		            	return;	//Nothing else to do.
		            }
		            if( ! currentPatient.mrn.equals(p.mrn) ) {
		            	//Patient has changed
		            	currentPatient=p;
		            }
		            
		            //deviceUdiToPatientMRN.put(c.unique_device_identifier, p);
		        }
		    }
			
		});
		
		Properties jdbcProps=new Properties();
        try {
        	
        	jdbcProps.load(new FileReader(new File(System.getProperty("user.home"),JDBC_PROPS_FILE_NAME)));
        	
        	String url=jdbcProps.getProperty("url");
        	String username=jdbcProps.getProperty("username");
        	String password=jdbcProps.getProperty("password");
        	dbconn = DriverManager.getConnection(url, username, password);
            
        } catch (FileNotFoundException fnfe) {
            log.warn("No JDBC properties file found",fnfe);
        } catch (IOException ioe) {
			log.warn("Could not read JDBC properties file", ioe);
		} catch (SQLException e) {
			log.warn("Could not connect to database - server probably not running",e);
		}
	}
	
//	private void addPumpToMainPanel(Device d) {
//		if(!udiToPump.containsKey(d.getUDI()) && numeric!=null) {
//			FXMLLoader loader = new FXMLLoader(PumpWithListener.class.getResource("PumpWithListener.fxml"));
//			try {
//		        final Parent ui = loader.load();
//		        
//		        final PumpWithListener controller = ((PumpWithListener) loader.getController());
//		        controller.setPump(d,numeric,writer, dbconn);
//		        pumps.getChildren().add(ui);
//		        udiToPump.put(d.getUDI(), ui);
//			} catch (IOException ioe) {
//				ioe.printStackTrace();
//			}
//		}
//	}
	
	/**
	 * Use this to allow access to the numeric sample that has a listener attached.
	 * Then if the pump is changed, the listener can be detached from the previous numeric
	 */
	private NumericFx currentPumpNumeric;
	
	private float[] getMinAndMax(Number[] numbers) {
		float[] minAndMax=new float[] {numbers[0].floatValue(),numbers[0].floatValue()};
		for(int i=1;i<numbers.length;i++) {
			if(numbers[i].floatValue()<minAndMax[0]) minAndMax[0]=numbers[i].floatValue();
			if(numbers[i].floatValue()>minAndMax[1]) minAndMax[1]=numbers[i].floatValue();
		}
		diastolicProperty.set((int)minAndMax[0]);
		systolicProperty.set((int)minAndMax[1]);
		return minAndMax;
	}
	
	class SampleValuesChangeListener implements ChangeListener<Number[]> {

		@Override
		public void changed(ObservableValue<? extends Number[]> observable, Number[] oldValue, Number[] newValue) {
			//Ignore the old values.  Just get new ones.
			float[] minMax=getMinAndMax(newValue);
			//System.err.println("got minMax as "+minMax[0]+ " and "+minMax[1]);
			//currentDiastolic.setText(Integer.toString((int)minMax[0]));
			//currentSystolic.setText(Integer.toString((int)minMax[1]));
			/*
			 * https://nursingcenter.com/ncblog/december-2011/calculating-the-map
			 */
			float meanCalc=(minMax[1]+(2*minMax[0]))/3;
			currentMean.setText(Integer.toString((int)meanCalc));
		}
	}
	
	SampleValuesChangeListener bpArrayListener=new SampleValuesChangeListener();
	
	/**
	 * Use this to allow access to the array sample that has a listener attached.
	 * Then if the BP monitor is changed, the listener can be detached from the previous sample
	 */
	private SampleArrayFx currentBPSample;
	
	private void handleBPDeviceChange(Device newDevice) {
		log.info("QCT.handleDeviceChange newDevice is "+newDevice);
		if(currentBPSample!=null) {
			currentBPSample.valuesProperty().removeListener(bpArrayListener);
		}
		if(null==newDevice) return;	//No device selected and/or available - can happen when patient is changed and no devices for that patient
		samples.forEach( s-> {
			if (! s.getUnique_device_identifier().contentEquals(newDevice.getUDI())) return;	//Some other device.
			//This sample is from the current device.
			if(s.getMetric_id().equals(ARTERIAL)) {
				s.valuesProperty().addListener(bpArrayListener);
				currentBPSample=s;
			}
		});
	}
	
	class DeviceListCell extends ListCell<Device> {
        @Override protected void updateItem(Device device, boolean empty) {
            super.updateItem(device, empty);
            if (!empty && device != null) {
                setText(device.getModel()+"("+device.getComPort()+")");
            } else {
                setText(null);
            }
        }
    }

//	public void refresh() {
//		int childCount=pumps.getChildren().size();
//		pumps.getChildren().remove(0, childCount);
//		activate();
//	}
	
	public void startProcess() {
		if(checkValid()) {
			runForMode();
		}
	}
	
	//TODO: is it better to have multiple alerts, one for each parameter - or a combined one?
	
	/*
	 * Using multiple seperate alerts, each included in its own validity checked method, allows for nicer
	 * and more modular code, but could be annoying for the user in the case of multiple invalid options.
	 * They won't see more than one alert at a time, because we will do
	 * 
	 * if(aValid() && bValid() && cValid().....)
	 * 
	 * but they might want all errors to be reported at once.  Maybe we extend the use of an error label to
	 * give a "live" status.  In theory, the values should be constrained by the factory used for the spinners,
	 * but...  
	 * 
	 * Anyway, in the meantime...
	 */
	
	
	private boolean checkValid() {
		if(openRadio.isSelected()) {
			//Open loop mode.
			return checkInfusionRate() && checkPumpSelected() && checkMonitorSelected();
		} else {
			//Closed loop mode.
			//Check patient selected
			//Check QCore selected
			//Check BP monitor selected
			//Check target BP range
			//Check infusion rate.
			return checkTargetBPRange() && checkInfusionRate() && checkPumpSelected() && checkMonitorSelected();
		}
	}
	
	private boolean checkTargetBPRange() {
		int systolicValue=(int)targetSystolic.getValue();
		if(systolicValue<LOWER_SYSTOLIC_LIMIT || systolicValue>UPPER_SYSTOLIC_LIMIT) {
			Alert alert=new Alert(AlertType.ERROR,"Target systolic must be between "+LOWER_SYSTOLIC_LIMIT+" and "+UPPER_SYSTOLIC_LIMIT,ButtonType.OK);
			alert.showAndWait();
			return false;
		}
		int diastolicValue=(int)targetDiastolic.getValue();
		if(diastolicValue<LOWER_DIASTOLIC_LIMIT || diastolicValue>UPPER_DIASTOLIC_LIMIT) {
			Alert alert=new Alert(AlertType.ERROR,"Target diastolic must be between "+LOWER_DIASTOLIC_LIMIT+" and "+UPPER_DIASTOLIC_LIMIT,ButtonType.OK);
			alert.showAndWait();
			return false;
		}
		return true;
	}
	
	private boolean checkInfusionRate() {
		double infusionRateValue=(double)infusionRate.getValue();
		if(infusionRateValue>=LOWER_INFUSION_LIMIT && infusionRateValue<=UPPER_INFUSION_LIMIT) {
			return true;
		} else {
			//Simpler to do the alert here as we know what mode we are in.
			Alert alert=new Alert(AlertType.ERROR,"In open-loop mode, infusion rate must be between "+LOWER_INFUSION_LIMIT+" and "+UPPER_INFUSION_LIMIT,ButtonType.OK);
			alert.showAndWait();
			return false;
		}
	}
	
	private boolean checkPumpSelected() {
		Device d=pumps.getSelectionModel().getSelectedItem();
		if(d==null) {
			Alert alert=new Alert(AlertType.ERROR,"A pump must be selected",ButtonType.OK);
			alert.showAndWait();
			return false;
		}
		return true;
	}
	
	private boolean checkMonitorSelected() {
		Device d=bpsources.getSelectionModel().getSelectedItem();
		if(d==null) {
			Alert alert=new Alert(AlertType.ERROR,"A BP monitor must be selected",ButtonType.OK);
			alert.showAndWait();
			return false;
		}
		return true;
	}
	
	private void runForMode() {
		//Get a whole graph thing...
		FXMLLoader loader = new FXMLLoader(Chart.class.getResource("Chart.fxml"));
        try {
        	
        	VitalModel myPrivateModel=null;
        	myPrivateModel=new VitalModelImpl(dlm, numeric);
        	VitalSign bothBP=VitalSign.BothBP;
        	Vital vitalForChart=bothBP.addToModel(myPrivateModel);
        	
        	
        	Parent node = loader.load();
            Chart chart = loader.getController();
//            Vital[] vitalForChart=new Vital[1];
//            VitalSign bothBP=VitalSign.BothBP;
//            boolean[] found=new boolean[1];
//            //Run through the Vitals in the VitalModel,
//            //and check if the one we want is already in the model.
//            vitalModel.forEach( v-> {
//            	if(v.getLabel().equals(bothBP.label)) {
//            		found[0]=true;
//            		vitalForChart[0]=v;
//            	}
//            });
//            if( ! found[0] ) {
//            	vitalForChart[0]=bothBP.addToModel(vitalModel);
//            }
//            for(String s : vitalForChart[0].getMetricIds()) {
//            	System.err.println("metricid for vitalForChart is "+s);
//            }
            
            
            long now = System.currentTimeMillis();
            now -= now % 1000;
            dateAxis=new DateAxis(new Date(now - interval), new Date(now));
//            dateAxis.setLowerBound();
//            dateAxis.setUpperBound();
            dateAxis.setAutoRanging(false);
            dateAxis.setAnimated(false);
            
            timeline = new Timeline(new KeyFrame(new Duration(1000.0), this));
            timeline.setCycleCount(Animation.INDEFINITE);
            timeline.play();
            
            
            //chart.setModel(vitalForChart[0], dateAxis);
            chart.setModel(vitalForChart, dateAxis);
            bpGraphBox.getChildren().add(node);
        } catch (Exception e) {
        	e.printStackTrace();
        }
		//bpGraphBox.getChildren().add();
        Device pump=pumps.getSelectionModel().getSelectedItem();
        NumericFx[] flowRateFromSelectedPump=new NumericFx[1];
        numeric.forEach( n -> {
        	if( n.getUnique_device_identifier().equals(pump.getUDI()) && n.getMetric_id().equals(FLOW_RATE)) {
        		//This is the flow rate from the pump we want
        		System.err.println("Found numeric for matching pump");
        		flowRateFromSelectedPump[0]=n;
        	}
        });
        
        Device monitor=bpsources.getSelectionModel().getSelectedItem();
        SampleArrayFx[] sampleFromSelectedMonitor=new SampleArrayFx[1];
        samples.forEach( s -> {
        	if(s.getUnique_device_identifier().equals(monitor.getUDI()) && s.getMetric_id().equals(ARTERIAL)) {
        		sampleFromSelectedMonitor[0]=s;
        	}
        });
        
        lastPumpUpdate.textProperty().bind(Bindings.format("Last pump update %s", flowRateFromSelectedPump[0].presentation_timeProperty()));
        lastBPUpdate.textProperty().bind(Bindings.format("Last BP update %s", sampleFromSelectedMonitor[0].presentation_timeProperty()));
		if(openRadio.isSelected()) {
			
		} else {
			
		}
	}
	
	@Override
	public void handle(ActionEvent arg0) {
		long now = System.currentTimeMillis();
        now -= now % 1000;
        Date lowerBound=new Date(now - interval);
        Date upperBound=new Date(now);
       	dateAxis.setLowerBound(lowerBound);
        dateAxis.setUpperBound(upperBound);	
		
	}
	
	private class BPAlarmMonitor extends Thread {
		
		@Override
		public void run() {
			//systolicProperty.
		}
		
	}


}
