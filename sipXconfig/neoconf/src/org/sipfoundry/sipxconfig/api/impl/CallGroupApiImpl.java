package org.sipfoundry.sipxconfig.api.impl;

import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.elasticsearch.common.lang3.StringUtils;
import org.sipfoundry.sipxconfig.api.CallGroupApi;
import org.sipfoundry.sipxconfig.api.model.CallGroupBean;
import org.sipfoundry.sipxconfig.api.model.CallGroupList;
import org.sipfoundry.sipxconfig.api.model.RingBean;
import org.sipfoundry.sipxconfig.callgroup.AbstractRing;
import org.sipfoundry.sipxconfig.callgroup.AbstractRing.Type;
import org.sipfoundry.sipxconfig.callgroup.CallGroup;
import org.sipfoundry.sipxconfig.callgroup.CallGroupContext;
import org.sipfoundry.sipxconfig.callgroup.UserRing;
import org.sipfoundry.sipxconfig.common.CoreContext;
import org.sipfoundry.sipxconfig.common.User;
import org.springframework.beans.factory.annotation.Required;

public class CallGroupApiImpl implements CallGroupApi {
	
	private static final Log LOG = LogFactory.getLog(CallGroupApiImpl.class);
	
	private CallGroupContext m_context;
	private CoreContext m_coreContext;
	
	@Override
	public Response getCallGroups() {
        List<CallGroup> callGroups = m_context.getCallGroups();
        if (callGroups != null) {
        	try {
        		return Response.ok().entity(CallGroupList.convertCallGroupList(callGroups)).build();
        	} catch (Exception ex) {
        		LOG.error("Exception building callgroup response ", ex);
        	}
        }
        return Response.status(Status.NOT_FOUND).build();
	}

	@Required
	public void setContext(CallGroupContext context) {
		m_context = context;
	}

	@Override
	public Response newCallGroup(CallGroupBean callGroupBean) {
		CallGroup callGroup = new CallGroup();
		convertToCallGroup(callGroupBean, callGroup);
		m_context.saveCallGroup(callGroup);
		return Response.ok().entity(callGroup.getId()).build();
	}
	
	public void convertToCallGroup(CallGroupBean callGroupBean, CallGroup callGroup) {        
        try {
            BeanUtils.copyProperties(callGroup, callGroupBean);
            if (callGroup.getRings().size() > 0) {
            	callGroup.clear();
            }
            List<RingBean> rings = callGroupBean.getRingBeans();
            for (RingBean ring : rings) {
            	UserRing userRing = callGroup.insertRingForUser(m_coreContext.loadUserByUserName(ring.getUserName()));
            	userRing.setEnabled(ring.isEnabled());
            	userRing.setExpiration(ring.getExpiration());
            	userRing.setType(AbstractRing.Type.getEnum(ring.getTypeStr()));            	
            }
        } catch (Exception e) {
            LOG.error("Cannot marshal properties");
        }
	}

	@Required
	public void setCoreContext(CoreContext coreContext) {
		m_coreContext = coreContext;
	}

	@Override
	public Response updateCallGroup(String callGroupExtension, CallGroupBean callGroupBean) {
		int id = m_context.getCallGroupId(callGroupExtension);
		CallGroup callGroup = m_context.loadCallGroup(id);
		if (callGroup != null) {
			convertToCallGroup(callGroupBean, callGroup);
			m_context.saveCallGroup(callGroup);
			return Response.ok().entity(callGroup.getId()).build();
		}
		return Response.status(Status.NOT_FOUND).build();
	}

	@Override
	public Response deleteCallGroup(String callGroupExtension) {
		m_context.removeCallGroupByAlias(callGroupExtension);
		return Response.ok().build();
	}

	@Override
	public Response getCallGroup(String callGroupExtension) {
        Integer callGroupId = m_context.getCallGroupIdByAlias(callGroupExtension);
        CallGroup callGroup = callGroupId != null ? m_context.loadCallGroup(callGroupId) : null;
        return callGroup!=null ? getCallGroup(callGroup) : Response.status(Status.NO_CONTENT).build();
	}
	
    public Response getCallGroup(CallGroup callGroup) {
        if (callGroup != null) {
            try {
				return Response.ok().entity(CallGroupBean.convertCallGroup(callGroup)).build();
			} catch (Exception e) {
				LOG.error("Cannot convert call group ", e);
				return Response.status(Status.INTERNAL_SERVER_ERROR).build();
			}
        }
        return Response.status(Status.NOT_FOUND).build();
    }

	@Override
	public Response duplicateCallGroup(String callGroupExtension, String assignedExtension) {
        int callGroupId = m_context.getCallGroupId(callGroupExtension);
        if (assignedExtension == null) {
        	ArrayList<Integer> list = new ArrayList<Integer>();
        	list.add(callGroupId);
        	m_context.duplicateCallGroups(list);
        } else {
        	m_context.duplicateCallGroup(callGroupId, assignedExtension);
        }
        return Response.ok().entity(callGroupId).build();
	}

	@Override
	public Response getPrefixedCallGroups(String prefix) {
        List<CallGroup> callGroups = m_context.getCallGroups();
        if (callGroups != null) {
        	try {
        		return Response.ok().entity(CallGroupList.convertCallGroupList(callGroups, prefix)).build();
        	} catch (Exception ex) {
        		LOG.error("Exception building callgroup response ", ex);
        	}
        }
        return Response.status(Status.INTERNAL_SERVER_ERROR).build();
	}

    @Override
    public Response rotateRings(String callGroupExtension, String ringExtension) {
    	int callGroupId = m_context.getCallGroupId(callGroupExtension);
    	CallGroup callGroup = m_context.loadCallGroup(callGroupId);
    	List<AbstractRing> rings = callGroup.getRings();
    	int ringsSize = rings.size();
    	UserRing lastRing = (ringsSize > 0) ? (UserRing)rings.get(ringsSize -1) : null;
    	if (lastRing != null && StringUtils.equals(lastRing.getUser().getExtension(true), ringExtension)) {
            LOG.debug("Hunt Group " + callGroupExtension + 
                            " ring rotation is not needed, extension to rotate is the last: " + ringExtension);
            return Response.ok().entity(callGroupId).build();
    	}
    
    	boolean found = false;
    	int size = rings.size();
    	for (int i = 0; i < size; i++) {
            UserRing userRing = (UserRing)rings.get(i);
            if (StringUtils.equals(ringExtension, userRing.getUser().getExtension(true))) {
                found = true;
            } else {
                if (found) {
                    for (int k = i; k < size; k++) {
                        for (int j = 0; j < i; j++) {
                            callGroup.moveRingUp(rings.get(k-j));
                        }
                    }
               }
           }
    	}
    
    	m_context.saveCallGroup(callGroup);
    	if (callGroup.getRings().size() > 0) {
            LOG.debug("Hunt Group " + callGroup.getExtension() + 
                            " now has first ring " + ((UserRing)callGroup.getRings().get(0)).getUser().getExtension(true));
    	} else {                                                                                                                                                                                                                             
            LOG.debug("Hunt Group " + callGroup.getExtension() + " has no rings");                                                                                                                                                       
    	}                                                                                                                                                                                                                                    
    	return Response.ok().entity(callGroupId).build();                                                                                                                                                                                    
    } 
}