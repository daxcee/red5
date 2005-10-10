package org.red5.server.context;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.red5.server.service.ServiceInvoker;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.support.FileSystemXmlApplicationContext;

public class AppContext 
	extends FileSystemXmlApplicationContext
	implements ApplicationContextAware {
	
	public static final String APP_CONFIG = "app.xml";
	
	protected String appPath;
	protected String appName;
	protected HostContext host;
		
	protected static Log log =
        LogFactory.getLog(AppContext.class.getName());
	
	public AppContext(HostContext host, String appName, String appPath) throws BeansException {
		super(new String[]{appPath + "/" + APP_CONFIG}, host);
		this.appName = appName;
		this.appPath = appPath;
	}

	public void setApplicationContext(ApplicationContext parent) throws BeansException {
		this.setParent(parent);
	}
	
	public ServiceInvoker getServiceInvoker(){
		return (ServiceInvoker) getBean(ServiceInvoker.SERVICE_NAME);
	}
	
	
}
