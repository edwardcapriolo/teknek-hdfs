/*
Copyright 2013 Edward Capriolo, Matt Landolf, Lodwin Cueto

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.teknek.driver;

import groovy.lang.Closure;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import io.teknek.collector.CollectorProcessor;
import io.teknek.feed.Feed;
import io.teknek.feed.FeedPartition;
import io.teknek.model.GroovyOperator;
import io.teknek.model.Operator;
import io.teknek.offsetstorage.Offset;
import io.teknek.offsetstorage.OffsetStorage;
import io.teknek.plan.FeedDesc;
import io.teknek.plan.OffsetStorageDesc;
import io.teknek.plan.OperatorDesc;
import io.teknek.plan.Plan;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

public class DriverFactory {

  public static Driver createDriver(FeedPartition feedPartition, Plan plan){
    OperatorDesc desc = plan.getRootOperator();
    Operator oper = null;
    try {
      oper = (Operator) Class.forName(desc.getOperatorClass()).newInstance();
    } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    OffsetStorage offsetStorage = null;
    OffsetStorageDesc offsetDesc = plan.getOffsetStorageDesc();
    if (offsetDesc != null && feedPartition.supportsOffsetManagement()){
      offsetStorage = buildOffsetStorage(feedPartition, plan, offsetDesc);
      Offset offset = offsetStorage.findLatestPersistedOffset();
      if (offset != null){
        feedPartition.setOffset(new String(offset.serialize()));
      }
    }
    CollectorProcessor cp = new CollectorProcessor();
    cp.setTupleRetry(plan.getTupleRetry());
    Driver driver = new Driver(feedPartition, oper, offsetStorage, cp);
    DriverNode root = driver.getDriverNode();
    
    recurseOperatorAndDriverNode(desc, root);
    return driver;
  }
  
  private static void recurseOperatorAndDriverNode(OperatorDesc desc, DriverNode node){
    List<OperatorDesc> children = desc.getChildren();
    for (OperatorDesc childDesc: children){
      Operator oper = null;
      try {
        oper = (Operator) Class.forName(childDesc.getOperatorClass()).newInstance();
      } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
      CollectorProcessor cp = new CollectorProcessor();
      cp.setTupleRetry(node.getCollectorProcessor().getTupleRetry());
      DriverNode childNode = new DriverNode(oper, cp);
      node.addChild(childNode);
      recurseOperatorAndDriverNode(childDesc, childNode);
    }
  }
  
  public static Operator buildOperator(OperatorDesc operatorDesc){
    Operator operator = null;
    if (operatorDesc.getSpec() == null){
      try {
        operator = (Operator) Class.forName(operatorDesc.getOperatorClass()).newInstance();
      } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
    } else if (operatorDesc.getSpec().equals("groovy")){
      GroovyClassLoader gc = new GroovyClassLoader();
      Class<?> c = gc.parseClass( operatorDesc.getScript()) ;
      try {
        operator = (Operator) c.newInstance();
      } catch (InstantiationException | IllegalAccessException e) {
        throw new RuntimeException (e);
      }
      try {
        gc.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else if (operatorDesc.getSpec().equals("groovyclosure")){
      GroovyShell shell = new GroovyShell();
      Object result = shell.evaluate(operatorDesc.getScript());
      if (result instanceof Closure){
        return new GroovyOperator((Closure) result);
      } else {
        throw new RuntimeException("result was wrong type "+ result);
      }
    } else {
      throw new RuntimeException(operatorDesc.getSpec() +" dont know how to handle that");
    }
    return operator;
  }
  
  public static OffsetStorage buildOffsetStorage(FeedPartition feedPartition, Plan plan, OffsetStorageDesc offsetDesc){
    OffsetStorage offsetStorage = null;
    Class [] paramTypes = new Class [] { FeedPartition.class, Plan.class, Map.class };    
    Constructor<OffsetStorage> offsetCons = null;
    try {
      offsetCons = (Constructor<OffsetStorage>) Class.forName(offsetDesc.getOperatorClass()).getConstructor(
              paramTypes);
    } catch (NoSuchMethodException | SecurityException | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    try {
      offsetStorage = offsetCons.newInstance(feedPartition, plan, offsetDesc.getParameters());
    } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
            | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
    return offsetStorage;
  }
  
}
