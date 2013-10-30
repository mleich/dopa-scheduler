package eu.stratosphere.meteor.client.connection;

import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConsumerCancelledException;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.QueueingConsumer;
import com.rabbitmq.client.ShutdownSignalException;

import eu.stratosphere.meteor.client.ClientConnectionFactory;
import eu.stratosphere.meteor.client.DOPAClient;
import eu.stratosphere.meteor.client.DSCLJob;
import eu.stratosphere.meteor.client.job.JobStateListener;
import eu.stratosphere.meteor.common.JobState;
import eu.stratosphere.meteor.common.MessageBuilder;

/**
 * This class extends the QueueingConsumer and handle deliveries in a special way.
 * The consumer sets the new status to the specified DSCLJob.
 *
 * @author André Greiner-Petter
 */
public class StatusConsumer extends QueueingConsumer {
	
	ClientConnectionFactory connFac;
	DOPAClient client;
	
	/**
	 * Super constructor
	 * @param ch
	 */
	public StatusConsumer( ClientConnectionFactory connFac, DOPAClient client, Channel ch ) {
		super(ch);
		this.connFac = connFac;
		this.client = client;
	}
	
	/**
	 * Message handle incoming deliveries. These messages are status updates. This consumer
	 * updates the states of jobs and invoke stateChanged if this job got one or more
	 * JobStateListeners.
	 */
	@Override
	public void handleDelivery( String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body )
			throws IOException {
		DOPAClient.LOG.info("Incoming delivery!");
		
		// forward delivery
		super.handleDelivery(consumerTag, envelope, properties, body);
		
		// try to handle new object
		try {
			// create status object
			JSONObject status = connFac.getStatus( 0 );
			
			// get informations to update specified job
			String jobID = status.getString("JobID");
			DSCLJob job = client.getJobList().get( jobID );
			JobState newStatus = MessageBuilder.getJobStatus(status);
			
			// update status
			job.setStatus( newStatus );
			
			// invoke listeners
			for ( JobStateListener listener : job.getListeners() )
				listener.stateChanged(job, newStatus );
		} catch (ShutdownSignalException | ConsumerCancelledException | InterruptedException | JSONException e) {
			DOPAClient.LOG.error("Cannot handle asynchronous status messages.", e);
		} 	
	}
}