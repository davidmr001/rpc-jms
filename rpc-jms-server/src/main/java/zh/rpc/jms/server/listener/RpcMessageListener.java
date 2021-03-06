package zh.rpc.jms.server.listener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import zh.rpc.jms.common.bean.RpcRequest;
import zh.rpc.jms.common.bean.RpcResponse;
import zh.rpc.jms.common.converter.MessageConverter;
import zh.rpc.jms.common.util.JmsUtils;
import zh.rpc.jms.common.util.SerializationUtil;
import zh.rpc.jms.server.annotation.RpcServiceParser;

public class RpcMessageListener implements MessageListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(RpcMessageListener.class);

	private Session session;

	private Executor taskExecutor;

	private RpcServiceParser rpcServiceParser;

	private MessageConverter messageConverter;

	public RpcMessageListener(Session session, Executor taskExecutor, MessageConverter messageConverter,
			RpcServiceParser rpcServiceParser) {
		this.session = session;
		this.taskExecutor = taskExecutor;
		this.messageConverter = messageConverter;
		this.rpcServiceParser = rpcServiceParser;
	}

	@Override
	public void onMessage(final Message message) {
		if (taskExecutor != null) {
			taskExecutor.execute(new Runnable() {
				public void run() {
					executeListener(session, message);
				}
			});
		} else {
			executeListener(session, message);
		}
	}

	protected void executeListener(Session session, Message message) {
		try {
			RpcRequest rpcRequest = getRpcRequest(message);
			RpcResponse rpcResponse = invokeAndCreateResult(rpcRequest);
			writeResponseMessage(session, message, rpcResponse);
		} catch (Throwable ex) {
			LOGGER.error("Execution of JMS message listener failed", ex);
		}
	}

	/**
	 * 回复客户端消息
	 * 
	 * @param session
	 * @param requestMessage
	 * @param rpcResponse
	 * @throws JMSException
	 */
	private void writeResponseMessage(Session session, Message requestMessage, RpcResponse rpcResponse)
			throws JMSException {
		Message response = createResponseMessage(session, requestMessage, rpcResponse);
		MessageProducer producer = session.createProducer(requestMessage.getJMSReplyTo());
		try {
			producer.send(response);
		} finally {
			JmsUtils.closeMessageProducer(producer);
		}
	}

	private RpcResponse invokeAndCreateResult(RpcRequest rpcRequest) {
		RpcResponse rpcResponse = new RpcResponse();
		rpcResponse.setRequestId(rpcRequest.getRequestId());
		try {
			rpcResponse.setResult(invoke(rpcRequest));
		} catch (Exception ex) {
			rpcResponse.setException(ex);
		}
		return rpcResponse;
	}

	/**
	 * 反射调用服务器本地方法
	 * 
	 * @param rpcRequest
	 * @return
	 * @throws NoSuchMethodException
	 * @throws SecurityException
	 * @throws IllegalAccessException
	 * @throws IllegalArgumentException
	 * @throws InvocationTargetException
	 */
	private Object invoke(RpcRequest rpcRequest) throws NoSuchMethodException, SecurityException,
			IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		String serviceName = rpcRequest.getInterfaceName();
		String serviceVersion = rpcRequest.getServiceVersion();
		Object serviceBean = rpcServiceParser.getService(serviceName, serviceVersion);
		if (serviceBean == null) {
			throw new RuntimeException(String.format("can not find service bean by key: %s", serviceName));
		}

		Class<?> serviceClass = serviceBean.getClass();
		String methodName = rpcRequest.getMethodName();
		Class<?>[] parameterTypes = rpcRequest.getParameterTypes();
		Object[] parameters = rpcRequest.getParameters();

		Method method = serviceClass.getMethod(methodName, parameterTypes);
		method.setAccessible(true);
		return method.invoke(serviceBean, parameters);
	}

	/**
	 * 反序列化Message为RpcRequest
	 * 
	 * @param message
	 * @return
	 * @throws JMSException
	 */
	private RpcRequest getRpcRequest(Message message) throws JMSException {
		byte messByte[] = (byte[]) messageConverter.fromMessage(message);
		RpcRequest rpcRequest = SerializationUtil.deserialize(messByte, RpcRequest.class);
		return rpcRequest;
	}

	/**
	 * 创建回复Message
	 * 
	 * @param session
	 * @param requestMessage
	 * @param rpcResponse
	 * @return
	 * @throws JMSException
	 */
	private Message createResponseMessage(Session session, Message requestMessage, RpcResponse rpcResponse)
			throws JMSException {
		Message responseMessage = messageConverter.toMessage(SerializationUtil.serialize(rpcResponse), session);
		String correlation = requestMessage.getJMSCorrelationID();
		if (correlation == null) {
			correlation = requestMessage.getJMSMessageID();
		}
		responseMessage.setJMSCorrelationID(correlation);
		return responseMessage;
	}

}
