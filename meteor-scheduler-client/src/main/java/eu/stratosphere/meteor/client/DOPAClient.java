package eu.stratosphere.meteor.client;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import eu.stratosphere.meteor.common.DSCLJob;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONObject;

import com.rabbitmq.client.ConsumerCancelledException;
import com.rabbitmq.client.ShutdownSignalException;

import eu.stratosphere.meteor.common.JobStateListener;
import eu.stratosphere.meteor.common.MessageBuilder;
import eu.stratosphere.meteor.common.JobState;

/**
 * 
 * This class represents the DOPA API client. Each client that connects
 * to the scheduler needs to have a unique clientID. You can create a client
 * by call the static method {@code createNewClient( final String clientID )}.
 * 
 * Before you can use this client object you have to connect it to the
 * scheduler, which is done using its {@code connect} method. Call any
 * other method before the client is connected an exception being thrown.
 * 
 * To see status informations about the client it implements a logger from
 * {@link org.apache.commons.logging.Log}. Note that the hadoop-core version
 * 0.20.2 bugs with a warning message about deprecated EventCounter. This bug
 * is fixed with (currently) beta versions higher then 0.20.2.
 * At the moment we use 0.20.205.0!
 *
 * @author 	André Greiner-Petter
 * 			Tieyan Shan
 *			Etienne Rolly
 */
public class DOPAClient {
	/**
	 * The log for client site.
	 */
	public static final Log LOG = LogFactory.getLog( DOPAClient.class );
	
	/**
	 * The unique clientID of this client object
	 */
	private final String clientID;
	
	/**
	 * The connection factory to handle all traffic from and to this client
	 */
	private ClientConnectionFactory connectionFac;
	
	/**
	 * A map of jobs from this client. The key represents the job ID.
	 */
	private HashMap<String, DSCLJob> jobs;
	
	/**
	 * The timeout time to connect with the scheduler system
	 */
	private int timeout = 5_000;

    private String host = null;

    private int port = -1;
	
	/**
	 * Constructs a new client object. This client isn't connected
	 * to scheduler yet.
	 * @param ID final unique identifier
	 */
	private DOPAClient( final String ID ) {
		this.clientID = ID;
		this.connectionFac = null;
		this.jobs = new HashMap<String, DSCLJob>();
	}
	
	/**
	 * Calculate a random ID.
	 * @return random ID
	 */
	public static String getRandomID(){
		return java.util.UUID.randomUUID().toString();
	}
	
	/**
	 * Returns the unique ID of this client object.
	 * @return ID
	 */
	public String getClientID(){
		return this.clientID;
	}
	
	/**
	 * Sets a new timeout for connection setups
	 * @param newTimeOut
	 */
	public void setTimeOut( int newTimeOut ){
		LOG.info("Sets the timeout from " + timeout + "ms to " + newTimeOut + "ms.");
		this.timeout = newTimeOut;
	}

    /**
     * sets the port for the connection, to use the default pass -1
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * sets the host for the connection, pass <code>null</code> to use the default
     */
    public void setHost(String host) {
        this.host = host;
    }
	
	/**
	 * Try to connect the client with the scheduler services.
	 * If this failed for any reason you can try it again.
	 * 
	 * It does nothing if the client is still connected.
     *
     * @return <code>true</code> if the client was successfully connected or the client was already connected,
     *      <code>false</code> if the client was not connected to the server
	 */
	public boolean connect() {
		// if the client is still connect
		if ( this.connectionFac != null ) {
			LOG.error( "The client is still connected. If you want to reconnect the client disconnect it first." );
			return true;
		}
		
		// else try to connect it
		try {
            if (host == null || port == -1) {
                this.connectionFac = new ClientConnectionFactory( this, timeout );
            } else {
                this.connectionFac = new ClientConnectionFactory( this, false, timeout, host, port);
            }
            return true;
        } catch ( InterruptedException iexc ){
        	LOG.warn("You are still not connected to the scheduler service, cause:" + System.lineSeparator() +
        			iexc.getMessage());
        } catch ( Exception exc ) {
            LOG.error( "Cannot connected to the scheduler services!" + System.lineSeparator(), exc );
        }
        return false;
	}
	
	/**
	 * Tries to reconnect the client with the scheduler services.
	 * Does nothing if this client is still connected.
     * Use this method if you want to reconnect to the scheduler but didn't properly disconnect before.
     * This is especially useful if you client crashed and the scheduler assumes it is still connected.
     *
     * ¡Use this method with great care!
     * We only support one active client instance per client ID. Reconnect only if you are sure the previous
     * client instance that created the connection is not running anymore!
     *
     * * @return <code>true</code> if the client was successfully reconnected or the client was already connected,
     *      <code>false</code> if the client was not reconnected to the server
	 */
	public boolean reconnect(){
		// if the client is still connect
		if ( this.connectionFac != null ) {
			LOG.error( "The client is still connected. If you want to reconnect the client disconnect it first." );
			return true;
		}

		// try to reconnect
		try {
            if (host == null || port == -1) {
                this.connectionFac = new ClientConnectionFactory( this, true, timeout );
            } else {
                this.connectionFac = new ClientConnectionFactory( this, true, timeout, host, port);
            }
            return true;
        }
		catch ( Exception exc ) {
            LOG.error("Cannot reconnect to the scheduler services!", exc);
        }
        return false;
	}

    /**
     * Check whether this client is connected to the server
     * @return <code>true</code> if this client is connected to the server
     */
    public boolean isConnected () {
        return this.connectionFac != null;
    }
	
	/**
	 * Try to disconnect the client. 
	 * It do nothing if the client isn't connected yet.
	 * 
	 * If this method failed for any reason you can try it again.
	 */
	public void disconnect() {
		// is the client connected?
		if ( this.connectionFac == null ){
			LOG.error("The client isn't connected. Please connect it first.");
			return;
		}
		
		// else try to shutdown the connections
		try { 
			this.connectionFac.shutDownConnection();
			this.connectionFac = null;
			LOG.info("Disconnected...");
		} catch (IOException e) {
			LOG.error( "Cannot close the connections or inform the scheduler: " + e.getMessage() , e);
		}
	}
	
	/**
	 * Returns an unmodifiable map of all jobs. If you try to change some entries
	 * you got an exception. You can find a DSCLJob object by its unique ID. This
	 * ID is the Key for this map.
	 * 
	 * @return unmodifiable map of current job objects
	 */
	public Map<String, DSCLJob> getJobList() {
		return Collections.unmodifiableMap( this.jobs );
	}
	
	/**
	 * This method submits a new job and returns this object. You can add no, one or a collection of 
	 * JobStateListener to this job object. The job objects got the current status of this job whether 
	 * you add one or more JobStateListener or not.
	 * 
	 * @param meteorScript to submit
	 * @param stateListener to inform state changes. You also can call this method in this way: {@code createNewJob( <String> );}
	 * @return DSCLJob object of the submitted job
	 */
	public DSCLJob createNewJob( String meteorScript, JobStateListener... stateListener ) {
		if ( this.connectionFac == null ) 
			throw new UnsupportedOperationException("Your client isn't connected yet!");
		
		// create a jobID
		String randomJobID = DOPAClient.getRandomID();
		
		// create a job object
		DSCLJobImpl job = new DSCLJobImpl( this.connectionFac, this.clientID, randomJobID, meteorScript );
		
		// add listeners
		for ( JobStateListener listener : stateListener )
			job.addJobStateListener( listener );
		
		// add jobs to internal list
		this.jobs.put( randomJobID, job );
		
		// try to submit
		try { this.connectionFac.submitJob(meteorScript, clientID, randomJobID ); } 
		catch (IOException ioe) { LOG.error( "Cannot submit the job. A traffic problem occured", ioe ); }
		
		// return the job object
		return job;
	}
	
	/**
	 * This method ask the scheduler whether the specified job exists on server side or not. 
	 * If it exists it adds the job to the current job list and add the stateListener to this object.
	 * Otherwise this method change the status of the job object to INITIALIZE. Please be sure
	 * you don't ask the scheduler for finished jobs as well. You find the current status (and whether
	 * the job still finished) in the DSCLJob object, just invoke getStatus().
	 * 
	 * @param jobID specified DSCLJob
	 * @param stateListener you can invoke reconnectJob(DSCLJob job) as well or you add so much
	 * 			listeners you want
	 */
	public DSCLJob reconnectJob( String jobID, JobStateListener... stateListener ){
		if ( this.connectionFac == null ) 
			throw new UnsupportedOperationException("Your client isn't connected yet!");
		
		try {
			// build request
			JSONObject requestObject = MessageBuilder.buildJobExistsRequest(clientID, jobID);
			String corrID = DOPAClient.getRandomID();
			
			// create new job object with state listeners
			DSCLJobImpl job = new DSCLJobImpl( this.connectionFac, this.clientID, jobID, null );
			for ( JobStateListener listener : stateListener )
				job.addJobStateListener( listener );
			
			// undefined status
			job.setStatus( JobState.UNDEFINED );
			
			// add job to internal list
			this.jobs.put( jobID, job);
			
			// send request
			this.connectionFac.sendRequest( null, requestObject, corrID );
			
			// return
			return job;
		} catch ( ShutdownSignalException | ConsumerCancelledException | InterruptedException trafficE ) {
			LOG.error("Communication failure!", trafficE);
			return null;
		} catch ( IOException ioE ) {
			LOG.fatal("Unknown IOException!", ioE);
			return null;
		}
	}
	
	/**
	 * Creates a new client object by a given clientID. This clientID is final
	 * and cannot changed while this client is alive.
	 * After you got the client object you have to connect it with
	 * the scheduler service. Call the method connect() to do this.
	 * 
	 * @param ID final unique identifier
	 * @return the client object (not connected yet)
	 */
	public static DOPAClient createNewClient( final String ID ) {
		return new DOPAClient( ID );
	}
	
	/**
	 * TODO
	 * This class is executable too but just to show how you create and connect a client object.
	 * @param args
	 */
	public static void main( String[] args ) {
		DOPAClient client = createNewClient( "Max Mustermann" );
		client.connect();
	}
}
