/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.jca.core.workmanager.transport.remote.socket;

import org.jboss.jca.core.CoreLogger;
import org.jboss.jca.core.spi.workmanager.Address;
import org.jboss.jca.core.workmanager.transport.remote.ProtocolMessages.Request;
import org.jboss.jca.core.workmanager.transport.remote.ProtocolMessages.Response;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.Socket;
import java.util.Set;

import javax.resource.spi.work.DistributableWork;
import javax.resource.spi.work.WorkException;

import org.jboss.logging.Logger;

/**
 * The communication between client and server
 * @author <a href="mailto:jesper.pedersen@jboss.org">Jesper Pedersen</a>
 */
public class Communication implements Runnable
{
   /** The logger */
   private static CoreLogger log = Logger.getMessageLogger(CoreLogger.class, Communication.class.getName());

   /** Trace logging */
   private static boolean trace = log.isTraceEnabled();

   /** The socket */
   private final Socket socket;

   /** The trasport **/
   private final SocketTransport transport;

   /**
    * Create a new Communication.
    *
    * @param socket The socket
    * @param transport The Transport
    */
   public Communication(SocketTransport transport, Socket socket)
   {
      this.socket = socket;
      this.transport = transport;
   }

   /**
    * Run
    */
   public void run()
   {
      ObjectInputStream ois = null;
      ObjectOutputStream oos = null;
      Serializable returnValue = null;
      Response response = null;
      try
      {
         ois = new ObjectInputStream(socket.getInputStream());
         int commandOrdinalPosition = ois.readInt();
         int numberOfParameters = ois.readInt();
         Serializable[] parameters = new Serializable[numberOfParameters];

         for (int i = 0; i < numberOfParameters; i++)
         {
            Serializable parameter = (Serializable)ois.readObject();
            parameters[i] = parameter;
         }

         Request command = Request.values()[commandOrdinalPosition];

         switch (command)
         {
            case JOIN : {
               String address = (String)parameters[0];

               if (trace)
                  log.tracef("%s: JOIN(%s)", socket.getInetAddress(), address);

               Set<Address> workManagers = 
                  (Set<Address>)transport.sendMessage(address, Request.GET_WORKMANAGERS);
               if (workManagers != null)
               {
                  for (Address a : workManagers)
                  {
                     transport.join(a, address);

                     long shortRunningFree =
                        (long)transport.sendMessage(address, Request.GET_SHORTRUNNING_FREE, a);
                     long longRunningFree =
                        (long)transport.sendMessage(address, Request.GET_LONGRUNNING_FREE, a);

                     transport.localUpdateShortRunningFree(a, shortRunningFree);
                     transport.localUpdateLongRunningFree(a, longRunningFree);
                  }
               }

               response = Response.OK_VOID;
               break;
            }
            case LEAVE : {
               String address = (String)parameters[0];

               if (trace)
                  log.tracef("%s: LEAVE(%s)", socket.getInetAddress(), address);

               transport.leave(address);
               response = Response.OK_VOID;
               break;
            }
            case GET_WORKMANAGERS : {
               if (trace)
                  log.tracef("%s: GET_WORKMANAGERS()", socket.getInetAddress());

               returnValue = (Serializable)transport.getAddresses(transport.getPhysicalAddress());
               response = Response.OK_SERIALIZABLE;

               break;
            }
            case PING : {
               if (trace)
                  log.tracef("%s: PING()", socket.getInetAddress());

               transport.localPing();
               response = Response.OK_VOID;

               break;
            }
            case DO_WORK : {
               Address id = (Address)parameters[0];
               DistributableWork work = (DistributableWork)parameters[1];

               if (trace)
                  log.tracef("%s: DO_WORK(%s, %s)", socket.getInetAddress(), id, work);

               transport.localDoWork(id, work);
               response = Response.OK_VOID;

               break;
            }
            case START_WORK : {
               Address id = (Address)parameters[0];
               DistributableWork work = (DistributableWork)parameters[1];

               if (trace)
                  log.tracef("%s: START_WORK(%s, %s)", socket.getInetAddress(), id, work);

               returnValue = transport.localStartWork(id, work);
               response = Response.OK_SERIALIZABLE;

               break;
            }
            case SCHEDULE_WORK : {
               Address id = (Address)parameters[0];
               DistributableWork work = (DistributableWork)parameters[1];

               if (trace)
                  log.tracef("%s: SCHEDULE_WORK(%s, %s)", socket.getInetAddress(), id, work);

               transport.localScheduleWork(id, work);
               response = Response.OK_VOID;

               break;
            }
            case GET_SHORTRUNNING_FREE : {
               Address id = (Address)parameters[0];

               if (trace)
                  log.tracef("%s: GET_SHORTRUNNING_FREE(%s)", socket.getInetAddress(), id);

               returnValue = transport.localGetShortRunningFree(id);
               response = Response.OK_SERIALIZABLE;

               break;
            }
            case GET_LONGRUNNING_FREE : {
               Address id = (Address)parameters[0];

               if (trace)
                  log.tracef("%s: GET_LONGRUNNING_FREE(%s)", socket.getInetAddress(), id);

               returnValue = transport.localGetLongRunningFree(id);
               response = Response.OK_SERIALIZABLE;

               break;
            }
            case UPDATE_SHORTRUNNING_FREE : {
               Address id = (Address)parameters[0];
               Long freeCount = (Long)parameters[1];

               if (trace)
                  log.tracef("%s: UPDATE_SHORTRUNNING_FREE(%s, %d)", socket.getInetAddress(), id, freeCount);

               transport.localUpdateShortRunningFree(id, freeCount);
               response = Response.OK_VOID;

               break;
            }
            case UPDATE_LONGRUNNING_FREE : {
               Address id = (Address)parameters[0];
               Long freeCount = (Long)parameters[1];

               if (trace)
                  log.tracef("%s: UPDATE_LONGRUNNING_FREE(%s, %d)", socket.getInetAddress(), id, freeCount);

               transport.localUpdateLongRunningFree(id, freeCount);
               response = Response.OK_VOID;

               break;
            }
            case GET_DISTRIBUTED_STATISTICS : {
               Address id = (Address)parameters[0];

               if (trace)
                  log.tracef("%s: GET_DISTRIBUTED_STATISTICS(%s)", socket.getInetAddress(), id);

               returnValue = transport.localGetDistributedStatistics(id);
               response = Response.OK_SERIALIZABLE;

               break;
            }
            case DELTA_DOWORK_ACCEPTED : {
               Address id = (Address)parameters[0];

               if (trace)
                  log.tracef("%s: DELTA_DOWORK_ACCEPTED(%s)", socket.getInetAddress(), id);

               transport.localDeltaDoWorkAccepted(id);
               response = Response.OK_VOID;

               break;
            }
            case DELTA_DOWORK_REJECTED : {
               Address id = (Address)parameters[0];

               if (trace)
                  log.tracef("%s: DELTA_DOWORK_REJECTED(%s)", socket.getInetAddress(), id);

               transport.localDeltaDoWorkRejected(id);
               response = Response.OK_VOID;

               break;
            }
            case DELTA_STARTWORK_ACCEPTED : {
               Address id = (Address)parameters[0];

               if (trace)
                  log.tracef("%s: DELTA_STARTWORK_ACCEPTED(%s)", socket.getInetAddress(), id);

               transport.localDeltaStartWorkAccepted(id);
               response = Response.OK_VOID;

               break;
            }
            case DELTA_STARTWORK_REJECTED : {
               Address id = (Address)parameters[0];

               if (trace)
                  log.tracef("%s: DELTA_STARTWORK_REJECTED(%s)", socket.getInetAddress(), id);

               transport.localDeltaStartWorkRejected(id);
               response = Response.OK_VOID;

               break;
            }
            case DELTA_SCHEDULEWORK_ACCEPTED : {
               Address id = (Address)parameters[0];

               if (trace)
                  log.tracef("%s: DELTA_SCHEDULEWORK_ACCEPTED(%s)", socket.getInetAddress(), id);

               transport.localDeltaScheduleWorkAccepted(id);
               response = Response.OK_VOID;

               break;
            }
            case DELTA_SCHEDULEWORK_REJECTED : {
               Address id = (Address)parameters[0];

               if (trace)
                  log.tracef("%s: DELTA_SCHEDULEWORK_REJECTED(%s)", socket.getInetAddress(), id);

               transport.localDeltaScheduleWorkRejected(id);
               response = Response.OK_VOID;

               break;
            }
            case DELTA_WORK_SUCCESSFUL : {
               Address id = (Address)parameters[0];

               if (trace)
                  log.tracef("%s: DELTA_WORK_SUCCESSFUL(%s)", socket.getInetAddress(), id);

               transport.localDeltaWorkSuccessful(id);
               response = Response.OK_VOID;

               break;
            }
            case DELTA_WORK_FAILED : {
               Address id = (Address)parameters[0];

               if (trace)
                  log.tracef("%s: DELTA_WORK_FAILED(%s)", socket.getInetAddress(), id);

               transport.localDeltaWorkFailed(id);
               response = Response.OK_VOID;

               break;
            }
            default :
               if (log.isDebugEnabled())
               {
                  log.debug("Unknown command received on socket Transport");
               }
               break;
         }

         if (response != null)
         {
            sendResponse(response, returnValue);
         }
         else
         {
            sendResponse(Response.GENERIC_EXCEPTION, new Exception("Unknown command: " + commandOrdinalPosition));
         }
      }
      catch (WorkException we)
      {
         if (trace)
            log.tracef("%s: WORK_EXCEPTION(%s)", socket.getInetAddress(), we);

         sendResponse(Response.WORK_EXCEPTION, we);
      }
      catch (Throwable t)
      {
         if (trace)
            log.tracef("%s: THROWABLE(%s)", socket.getInetAddress(), t);

         sendResponse(Response.GENERIC_EXCEPTION, t);
      }
      finally
      {
         if (ois != null)
         {
            try
            {
               ois.close();
            }
            catch (IOException e)
            {
               //ignore it
            }
         }
      }
   }

   private void sendResponse(Response response, Serializable... parameters)
   {
      ObjectOutputStream oos = null;
      try
      {
         oos = new ObjectOutputStream(socket.getOutputStream());
         oos.writeInt(response.ordinal());
         oos.writeInt(response.getNumberOfParameter());
         if (response.getNumberOfParameter() > 0 && parameters != null)
         {
            for (Serializable o : parameters)
            {
               oos.writeObject(o);
            }
         }

         oos.flush();

      }
      catch (Throwable t)
      {
         if (log.isDebugEnabled())
         {
            log.debug("error sending command");
         }
      }
      finally
      {
         if (oos != null)
         {
            try
            {
               oos.close();
            }
            catch (IOException e)
            {
               //ignore it
            }
         }
      }
   }
}
