/*
 * Copyright 2012 Database Lab., Korea Univ.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tajo.engine.planner.physical;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.codec.binary.Base64;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import tajo.*;
import tajo.catalog.*;
import tajo.catalog.proto.CatalogProtos.DataType;
import tajo.catalog.proto.CatalogProtos.StoreType;
import tajo.conf.TajoConf;
import tajo.datum.Datum;
import tajo.datum.DatumFactory;
import tajo.datum.NullDatum;
import tajo.engine.parser.QueryAnalyzer;
import tajo.engine.planner.LogicalOptimizer;
import tajo.engine.planner.LogicalPlanner;
import tajo.engine.planner.PlannerUtil;
import tajo.engine.planner.PlanningContext;
import tajo.engine.planner.logical.LogicalNode;
import tajo.engine.planner.logical.LogicalRootNode;
import tajo.engine.planner.logical.StoreTableNode;
import tajo.engine.planner.logical.UnionNode;
import tajo.engine.planner2.PhysicalPlanner;
import tajo.engine.planner2.PhysicalPlannerImpl;
import tajo.engine.planner2.physical.ExternalSortExec;
import tajo.engine.planner2.physical.IndexedStoreExec;
import tajo.engine.planner2.physical.PhysicalExec;
import tajo.engine.planner2.physical.ProjectionExec;
import tajo.master.SubQuery;
import tajo.master.TajoMaster;
import tajo.storage.*;
import tajo.storage.index.bst.BSTIndex;
import tajo.util.TUtil;
import tajo.worker.RangeRetrieverHandler;
import tajo.worker.dataserver.retriever.FileChunk;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class TestPhysicalPlanner {
  private static TajoTestingCluster util;
  private static TajoConf conf;
  private static CatalogService catalog;
  private static QueryAnalyzer analyzer;
  private static LogicalPlanner planner;
  private static StorageManager sm;

  private static TableDesc employee = null;
  private static TableDesc student = null;
  private static TableDesc score = null;

  @BeforeClass
  public static void setUp() throws Exception {
    QueryIdFactory.reset();
    util = new TajoTestingCluster();

    util.startCatalogCluster();
    conf = util.getConfiguration();
    sm = StorageManager.get(conf, new Path(util.setupClusterTestBuildDir().toURI()));
    catalog = util.getMiniCatalogCluster().getCatalog();
    for (FunctionDesc funcDesc : TajoMaster.initBuiltinFunctions()) {
      catalog.registerFunction(funcDesc);
    }


    Schema schema = new Schema();
    schema.addColumn("name", DataType.STRING);
    schema.addColumn("empId", DataType.INT);
    schema.addColumn("deptName", DataType.STRING);

    Schema schema2 = new Schema();
    schema2.addColumn("deptName", DataType.STRING);
    schema2.addColumn("manager", DataType.STRING);

    Schema scoreSchema = new Schema();
    scoreSchema.addColumn("deptName", DataType.STRING);
    scoreSchema.addColumn("class", DataType.STRING);
    scoreSchema.addColumn("score", DataType.INT);
    scoreSchema.addColumn("nullable", DataType.STRING);

    TableMeta employeeMeta = TCatUtil.newTableMeta(schema, StoreType.CSV);

    sm.initTableBase(employeeMeta, "employee");
    Appender appender = sm.getAppender(employeeMeta, "employee", "employee");
    Tuple tuple = new VTuple(employeeMeta.getSchema().getColumnNum());
    for (int i = 0; i < 100; i++) {
      tuple.put(new Datum[] {DatumFactory.createString("name_" + i),
          DatumFactory.createInt(i), DatumFactory.createString("dept_" + i)});
      appender.addTuple(tuple);
    }
    appender.flush();
    appender.close();

    employee = new TableDescImpl("employee", employeeMeta, 
        sm.getTablePath("employee"));
    catalog.addTable(employee);

    student = new TableDescImpl("dept", schema2, StoreType.CSV, new Options(),
        new Path("file:///"));
    catalog.addTable(student);

    score = new TableDescImpl("score", scoreSchema, StoreType.CSV, 
        new Options(), sm.getTablePath("score"));
    sm.initTableBase(score.getMeta(), "score");
    appender = sm.getAppender(score.getMeta(), "score", "score");
    tuple = new VTuple(score.getMeta().getSchema().getColumnNum());
    int m = 0;
    for (int i = 1; i <= 5; i++) {
      for (int k = 3; k < 5; k++) {
        for (int j = 1; j <= 3; j++) {
          tuple.put(
              new Datum[] {
                  DatumFactory.createString("name_" + i), // name_1 ~ 5 (cad: // 5)
                  DatumFactory.createString(k + "rd"), // 3 or 4rd (cad: 2)
                  DatumFactory.createInt(j), // 1 ~ 3
              m % 3 == 1 ? DatumFactory.createString("one") : NullDatum.get()});
          appender.addTuple(tuple);
          m++;
        }
      }
    }
    appender.flush();
    appender.close();
    catalog.addTable(score);
    analyzer = new QueryAnalyzer(catalog);
    planner = new LogicalPlanner(catalog);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    util.shutdownCatalogCluster();
  }

  private String[] QUERIES = {
      "select name, empId, deptName from employee", // 0
      "select name, empId, e.deptName, manager from employee as e, dept as dp", // 1
      "select name, empId, e.deptName, manager, score from employee as e, dept, score", // 2
      "select p.deptName, sum(score) from dept as p, score group by p.deptName having sum(score) > 30", // 3
      "select p.deptName, score from dept as p, score order by score asc", // 4
      "select name from employee where empId = 100", // 5
      "select deptName, class, score from score", // 6
      "select deptName, class, sum(score), max(score), min(score) from score group by deptName, class", // 7
      "select count(*), max(score), min(score) from score", // 8
      "select count(deptName) from score", // 9
      "select managerId, empId, deptName from employee order by managerId, empId desc", // 10
      "select deptName, nullable from score group by deptName, nullable", // 11
      "select 3 < 4 as ineq, 3.5 * 2 as real", // 12
//      "select (3 > 2) = (1 > 0) and 3 > 1", // 12
      "select (1 > 0) and 3 > 1", // 13
      "select deptName, class, sum(score), max(score), min(score) from score", // 14
      "select deptname, class, sum(score), max(score), min(score) from score group by deptname" // 15
  };

  @Test
  public final void testCreateScanPlan() throws IOException {
    Fragment[] frags = sm.split("employee");
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testCreateScanPlan");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil
        .newQueryUnitAttemptId(),
        new Fragment[] { frags[0] }, workDir);
    PlanningContext context = analyzer.parse(QUERIES[0]);
    LogicalNode plan = planner.createPlan(context);

    LogicalOptimizer.optimize(context, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);

    Tuple tuple;
    int i = 0;
    exec.init();
    while ((tuple = exec.next()) != null) {
      assertTrue(tuple.contains(0));
      assertTrue(tuple.contains(1));
      assertTrue(tuple.contains(2));
      i++;
    }
    exec.close();
    assertEquals(100, i);
  }

  @Test
  public final void testGroupByPlan() throws IOException {
    Fragment[] frags = sm.split("score");
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testGroupByPlan");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] { frags[0] }, workDir);
    PlanningContext context = analyzer.parse(QUERIES[7]);
    LogicalNode plan = planner.createPlan(context);
    plan = LogicalOptimizer.optimize(context, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);

    int i = 0;
    Tuple tuple;
    exec.init();
    while ((tuple = exec.next()) != null) {
      assertEquals(6, tuple.get(2).asInt()); // sum
      assertEquals(3, tuple.get(3).asInt()); // max
      assertEquals(1, tuple.get(4).asInt()); // min
      i++;
    }
    exec.close();
    assertEquals(10, i);
  }

  @Test
  public final void testHashGroupByPlanWithALLField() throws IOException {
    // TODO - currently, this query does not use hash-based group operator.
    Fragment[] frags = sm.split("score");
    Path workDir = WorkerTestingUtil.buildTestDir(
        "target/test-data/testHashGroupByPlanWithALLField");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] { frags[0] }, workDir);
    PlanningContext context = analyzer.parse(QUERIES[15]);
    LogicalNode plan = planner.createPlan(context);
    plan = LogicalOptimizer.optimize(context, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);

    int i = 0;
    Tuple tuple;
    exec.init();
    while ((tuple = exec.next()) != null) {
      assertEquals(DatumFactory.createNullDatum(), tuple.get(1));
      assertEquals(12, tuple.get(2).asInt()); // sum
      assertEquals(3, tuple.get(3).asInt()); // max
      assertEquals(1, tuple.get(4).asInt()); // min
      i++;
    }
    exec.close();
    assertEquals(5, i);
  }

  @Test
  public final void testSortGroupByPlan() throws IOException {
    Fragment[] frags = sm.split("score");
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testSortGroupByPlan");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[]{frags[0]}, workDir);
    PlanningContext context = analyzer.parse(QUERIES[7]);
    LogicalNode plan = planner.createPlan(context);
    plan = LogicalOptimizer.optimize(context, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);

    /*HashAggregateExec hashAgg = (HashAggregateExec) exec;

    SeqScanExec scan = (SeqScanExec) hashAgg.getSubOp();

    Column [] grpColumns = hashAgg.getAnnotation().getGroupingColumns();
    QueryBlock.SortSpec [] specs = new QueryBlock.SortSpec[grpColumns.length];
    for (int i = 0; i < grpColumns.length; i++) {
      specs[i] = new QueryBlock.SortSpec(grpColumns[i], true, false);
    }
    SortNode annotation = new SortNode(specs);
    annotation.setInSchema(scan.getSchema());
    annotation.setOutSchema(scan.getSchema());
    SortExec sort = new SortExec(annotation, scan);
    exec = new SortAggregateExec(hashAgg.getAnnotation(), sort);*/

    int i = 0;
    Tuple tuple;
    exec.init();
    while ((tuple = exec.next()) != null) {
      assertEquals(6, tuple.get(2).asInt()); // sum
      assertEquals(3, tuple.get(3).asInt()); // max
      assertEquals(1, tuple.get(4).asInt()); // min
      i++;
    }
    assertEquals(10, i);

    exec.rescan();
    i = 0;
    while ((tuple = exec.next()) != null) {
      assertEquals(6, tuple.getInt(2).asInt()); // sum
      assertEquals(3, tuple.getInt(3).asInt()); // max
      assertEquals(1, tuple.getInt(4).asInt()); // min
      i++;
    }
    exec.close();
    assertEquals(10, i);
  }

  private String[] CreateTableAsStmts = {
      "create table grouped1 as select deptName, class, sum(score), max(score), min(score) from score group by deptName, class", // 8
      "create table grouped2 using rcfile as select deptName, class, sum(score), max(score), min(score) from score group by deptName, class", // 8
  };

  @Test
  public final void testStorePlan() throws IOException {
    Fragment[] frags = sm.split("score");
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testStorePlan");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] { frags[0] },
        workDir);
    ctx.setOutputPath(new Path(workDir, "grouped1"));

    PlanningContext context = analyzer.parse(CreateTableAsStmts[0]);
    LogicalNode plan = planner.createPlan(context);
    plan = LogicalOptimizer.optimize(context, plan);

    TableMeta outputMeta = TCatUtil.newTableMeta(plan.getOutSchema(),
        StoreType.CSV);
    sm.initTableBase(outputMeta, "grouped1");

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);
    exec.init();
    exec.next();
    exec.close();

    Scanner scanner = sm.getScannerNG("grouped1", outputMeta, ctx.getOutputPath());
    Tuple tuple;
    int i = 0;
    while ((tuple = scanner.next()) != null) {
      assertEquals(6, tuple.get(2).asInt()); // sum
      assertEquals(3, tuple.get(3).asInt()); // max
      assertEquals(1, tuple.get(4).asInt()); // min
      i++;
    }
    assertEquals(10, i);
    scanner.close();

    // Examine the statistics information
    assertEquals(10, ctx.getResultStats().getNumRows().longValue());
  }

  @Test
  public final void testStorePlanWithRCFile() throws IOException {
    Fragment[] frags = sm.split("score");
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testStorePlanWithRCFile");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] { frags[0] },
        workDir);
    ctx.setOutputPath(new Path(workDir, "grouped2"));

    PlanningContext context = analyzer.parse(CreateTableAsStmts[1]);
    LogicalNode plan = planner.createPlan(context);
    plan = LogicalOptimizer.optimize(context, plan);

    TableMeta outputMeta = TCatUtil.newTableMeta(plan.getOutSchema(),
        StoreType.RCFILE);
    sm.initTableBase(outputMeta, "grouped2");

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);
    exec.init();
    exec.next();
    exec.close();

    Scanner scanner = sm.getScannerNG("grouped2", outputMeta, ctx.getOutputPath());
    Tuple tuple;
    int i = 0;
    while ((tuple = scanner.next()) != null) {
      assertEquals(6, tuple.get(2).asInt()); // sum
      assertEquals(3, tuple.get(3).asInt()); // max
      assertEquals(1, tuple.get(4).asInt()); // min
      i++;
    }
    assertEquals(10, i);
    scanner.close();

    // Examine the statistics information
    assertEquals(10, ctx.getResultStats().getNumRows().longValue());
  }

  class PathFilterWithoutMeta implements PathFilter {

    @Override
    public boolean accept(Path path) {
      String name = path.getName();
      if (name.startsWith(".")) {
        return false;
      } else {
        return true;
      }
    }
  }

  @Test
  public final void testPartitionedStorePlan() throws IOException {
    Fragment[] frags = sm.split("score");
    QueryUnitAttemptId id = TUtil.newQueryUnitAttemptId();
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testPartitionedStorePlan");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, id, new Fragment[] { frags[0] },
        workDir);
    PlanningContext context = analyzer.parse(QUERIES[7]);
    LogicalNode plan = planner.createPlan(context);

    int numPartitions = 3;
    Column key1 = new Column("score.deptName", DataType.STRING);
    Column key2 = new Column("score.class", DataType.STRING);
    StoreTableNode storeNode = new StoreTableNode("partition");
    storeNode.setPartitions(SubQuery.PARTITION_TYPE.HASH, new Column[]{key1, key2}, numPartitions);
    PlannerUtil.insertNode(plan, storeNode);
    plan = LogicalOptimizer.optimize(context, plan);

    TableMeta outputMeta = TCatUtil.newTableMeta(plan.getOutSchema(),
        StoreType.CSV);

    FileSystem fs = sm.getFileSystem();
    fs.mkdirs(new Path(workDir, "partition"));
    //sm.initTableBase(outputMeta, "partition");

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);
    exec.init();
    exec.next();
    exec.close();

    Path path = new Path(workDir, "output");
    FileStatus [] list = fs.listStatus(path, new PathFilterWithoutMeta());
    assertEquals(numPartitions, list.length);

    Fragment [] fragments = new Fragment[list.length];
    int i = 0;
    for (FileStatus status : list) {
      fragments[i++] = new Fragment("partition", status.getPath(), outputMeta, 0, status.getLen(), null);
    }
    Scanner scanner = new CSVFile2.CSVScanner(conf, outputMeta.getSchema(),fragments);

    Tuple tuple;
    i = 0;
    while ((tuple = scanner.next()) != null) {
      assertEquals(6, tuple.get(2).asInt()); // sum
      assertEquals(3, tuple.get(3).asInt()); // max
      assertEquals(1, tuple.get(4).asInt()); // min
      i++;
    }
    assertEquals(10, i);
    scanner.close();

    // Examine the statistics information
    assertEquals(10, ctx.getResultStats().getNumRows().longValue());
  }

  @Test
  public final void testPartitionedStorePlanWithEmptyGroupingSet()
      throws IOException {
    Fragment[] frags = sm.split("score");
    QueryUnitAttemptId id = TUtil.newQueryUnitAttemptId();

    Path workDir = WorkerTestingUtil.buildTestDir(
        "target/test-data/testPartitionedStorePlanWithEmptyGroupingSet");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, id, new Fragment[] { frags[0] },
        workDir);
    PlanningContext context = analyzer.parse(QUERIES[14]);
    LogicalNode plan = planner.createPlan(context);

    int numPartitions = 1;
    StoreTableNode storeNode = new StoreTableNode("emptyset");
    storeNode.setPartitions(SubQuery.PARTITION_TYPE.HASH, new Column[] {}, numPartitions);
    PlannerUtil.insertNode(plan, storeNode);
    plan = LogicalOptimizer.optimize(context, plan);

    TableMeta outputMeta = TCatUtil.newTableMeta(plan.getOutSchema(),
        StoreType.CSV);
    sm.initTableBase(outputMeta, "emptyset");

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);
    exec.init();
    exec.next();
    exec.close();

    Path path = new Path(workDir, "output");
    FileSystem fs = sm.getFileSystem();

    FileStatus [] list = fs.listStatus(path, new PathFilterWithoutMeta());
    assertEquals(numPartitions, list.length);

    Fragment [] fragments = new Fragment[list.length];
    int i = 0;
    for (FileStatus status : list) {
      fragments[i++] = new Fragment("partition", status.getPath(), outputMeta, 0, status.getLen(), null);
    }
    Scanner scanner = new CSVFile2.CSVScanner(conf, outputMeta.getSchema(),fragments);
    Tuple tuple;
    i = 0;
    while ((tuple = scanner.next()) != null) {
      assertEquals(60, tuple.get(2).asInt()); // sum
      assertEquals(3, tuple.get(3).asInt()); // max
      assertEquals(1, tuple.get(4).asInt()); // min
      i++;
    }
    assertEquals(1, i);
    scanner.close();

    // Examine the statistics information
    assertEquals(1, ctx.getResultStats().getNumRows().longValue());
  }

  //@Test
  public final void testAggregationFunction() throws IOException {
    Fragment[] frags = sm.split("score");
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testAggregationFunction");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] { frags[0] }, workDir);
    PlanningContext context = analyzer.parse(QUERIES[8]);
    LogicalNode plan = planner.createPlan(context);
    plan = LogicalOptimizer.optimize(context, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);

    exec.init();
    Tuple tuple = exec.next();
    assertEquals(30, tuple.get(0).asLong());
    assertEquals(3, tuple.get(1).asInt());
    assertEquals(1, tuple.get(2).asInt());
    assertNull(exec.next());
    exec.close();
  }

  //@Test
  public final void testCountFunction() throws IOException {
    Fragment[] frags = sm.split("score");
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testCountFunction");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] { frags[0] }, workDir);
    PlanningContext context = analyzer.parse(QUERIES[10]);
    LogicalNode plan = planner.createPlan(context);
    plan = LogicalOptimizer.optimize(context, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);

    Tuple tuple = exec.next();
    assertEquals(30, tuple.get(0).asLong());
    assertNull(exec.next());
  }

  @Test
  public final void testGroupByWithNullValue() throws IOException {
    Fragment[] frags = sm.split("score");
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testGroupByWithNullValue");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] { frags[0] }, workDir);
    PlanningContext context = analyzer.parse(QUERIES[11]);
    LogicalNode plan = planner.createPlan(context);
    plan = LogicalOptimizer.optimize(context, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);

    int count = 0;
    exec.init();
    while(exec.next() != null) {
      count++;
    }
    exec.close();
    assertEquals(10, count);
  }

  @Test
  public final void testUnionPlan() throws IOException {
    Fragment[] frags = sm.split("employee");
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testUnionPlan");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] { frags[0] }, workDir);
    PlanningContext context = analyzer.parse(QUERIES[0]);
    LogicalNode plan = planner.createPlan(context);
    plan = LogicalOptimizer.optimize(context, plan);
    LogicalRootNode root = (LogicalRootNode) plan;
    UnionNode union = new UnionNode(root.getSubNode(), root.getSubNode());
    root.setSubNode(union);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, root);

    int count = 0;
    exec.init();
    while(exec.next() != null) {
      count++;
    }
    exec.close();
    assertEquals(200, count);
  }

  @Test
  public final void testEvalExpr() throws IOException {
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testEvalExpr");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] { }, workDir);
    PlanningContext context = analyzer.parse(QUERIES[12]);
    LogicalNode plan = planner.createPlan(context);
    LogicalOptimizer.optimize(context, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf, sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);
    Tuple tuple;
    exec.init();
    tuple = exec.next();
    exec.close();
    assertEquals(true, tuple.get(0).asBool());
    assertTrue(7.0d == tuple.get(1).asDouble());

    context = analyzer.parse(QUERIES[13]);
    plan = planner.createPlan(context);
    LogicalOptimizer.optimize(context, plan);

    phyPlanner = new PhysicalPlannerImpl(conf, sm);
    exec = phyPlanner.createPlan(ctx, plan);
    exec.init();
    tuple = exec.next();
    exec.close();
    assertEquals(DatumFactory.createBool(true), tuple.get(0));
  }

  public final String [] createIndexStmt = {
      "create index idx_employee on employee using bst (name null first, empId desc)"
  };

  @Test
  public final void testCreateIndex() throws IOException {
    Fragment[] frags = sm.split("employee");
    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testCreateIndex");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] {frags[0]}, workDir);
    PlanningContext context = analyzer.parse(createIndexStmt[0]);
    LogicalNode plan = planner.createPlan(context);
    LogicalOptimizer.optimize(context, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf, sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);
    exec.init();
    while (exec.next() != null) {
    }
    exec.close();

    Path path = sm.getTablePath("employee");
    FileStatus [] list = sm.getFileSystem().listStatus(new Path(path, "index"));
    assertEquals(2, list.length);
  }

  final static String [] duplicateElimination = {
      "select distinct deptname from score",
  };

  @Test
  public final void testDuplicateEliminate() throws IOException {
    Fragment[] frags = sm.split("score");

    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testDuplicateEliminate");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] {frags[0]}, workDir);
    PlanningContext context = analyzer.parse(duplicateElimination[0]);
    LogicalNode plan = planner.createPlan(context);
    LogicalOptimizer.optimize(context, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);
    Tuple tuple;

    int cnt = 0;
    Set<String> expected = Sets.newHashSet(
        "name_1", "name_2", "name_3", "name_4", "name_5");
    exec.init();
    while ((tuple = exec.next()) != null) {
      assertTrue(expected.contains(tuple.getString(0).asChars()));
      cnt++;
    }
    exec.close();
    assertEquals(5, cnt);
  }

  public String [] SORT_QUERY = {
      "select name, empId from employee order by empId"
  };

  /*
  @Test
  public final void testBug() throws IOException {
    Schema s1 = new Schema();
    s1.addColumn("o_orderdate", DataType.STRING);
    s1.addColumn("o_shippriority", DataType.INT);
    s1.addColumn("o_orderkey", DataType.LONG);

    Options opt = new Options();
    opt.put(CSVFile2.DELIMITER, "|");
    TableMeta meta1 = new TableMetaImpl(s1, StoreType.CSV, opt);
    TableDesc desc1 = new TableDescImpl("s1", meta1, new Path("file:/home/hyunsik/error/sample/sq_1358404721340_0001_000001_03"));

    Schema s2 = new Schema();
    s2.addColumn("l_orderkey", DataType.LONG);
    s2.addColumn("l_extendedprice", DataType.DOUBLE);
    s2.addColumn("l_discount", DataType.DOUBLE);
    TableMeta meta2 = new TableMetaImpl(s2, StoreType.CSV, opt);
    TableDesc desc2 = new TableDescImpl("s2", meta1, new Path("file:/home/hyunsik/error/sample/sq_1358404721340_0001_000001_04"));

    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testBug");


    Fragment [] frag1 = sm.splitNG("sq_1358404721340_0001_000001_03", meta1, new Path("file:/home/hyunsik/error/sample/sq_1358404721340_0001_000001_03"), util.getDefaultFileSystem().getDefaultBlockSize());
    Fragment [] frag2 = sm.splitNG("sq_1358404721340_0001_000001_04", meta2, new Path("file:/home/hyunsik/error/sample/sq_1358404721340_0001_000001_04"), util.getDefaultFileSystem().getDefaultBlockSize());

    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(), null, workDir);
    PlanningContext context = analyzer.parse("select l_orderkey,  sum(l_extendedprice*(1-l_discount)) as revenue, o_orderdate, o_shippriority from s1, s2 where l_orderkey (LONG) = o_orderkey group by l_orderkey, o_orderdate, o_shippriority order by o_orderdate");
    LogicalNode plan = planner.createPlan(context);
    plan = LogicalOptimizer.optimize(context, plan);
    JoinNode joinNode = (JoinNode) PlannerUtil.findTopNode(plan, ExprType.JOIN);

    ScanNode [] scanNodes = (ScanNode[]) PlannerUtil.findAllNodes(plan, ExprType.SCAN);
    System.out.println(scanNodes[0].getTableId());
    System.out.println(scanNodes[1].getTableId());
//    SeqScanExec seqScanExec = new SeqScanExec(ctx, sm, plan, frag1);
//
//    HashJoinExec join = new HashJoinExec(ctx, joinNode, scan1, scan2);
//
//    System.out.println(scan1.next());
//    System.out.println(scan2.next());
  }*/

  @Test
  public final void testIndexedStoreExec() throws IOException {
    Fragment[] frags = sm.split("employee");

    Path workDir = WorkerTestingUtil.buildTestDir("target/test-data/testIndexedStoreExec");
    TaskAttemptContext2 ctx = new TaskAttemptContext2(conf, TUtil.newQueryUnitAttemptId(),
        new Fragment[] {frags[0]}, workDir);
    PlanningContext context = analyzer.parse(SORT_QUERY[0]);
    LogicalNode plan = planner.createPlan(context);
    plan = LogicalOptimizer.optimize(context, plan);

    PhysicalPlanner phyPlanner = new PhysicalPlannerImpl(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);

    ProjectionExec proj = (ProjectionExec) exec;
    ExternalSortExec sort = (ExternalSortExec) proj.getChild();

    SortSpec[] sortSpecs = sort.getPlan().getSortKeys();
    IndexedStoreExec idxStoreExec = new IndexedStoreExec(ctx, sm, sort, sort.getSchema(), sort.getSchema(), sortSpecs);

    Tuple tuple;
    exec = idxStoreExec;
    exec.init();
    exec.next();
    exec.close();

    Schema keySchema = new Schema();
    keySchema.addColumn("?empId", DataType.INT);
    SortSpec[] sortSpec = new SortSpec[1];
    sortSpec[0] = new SortSpec(keySchema.getColumn(0), true, false);
    TupleComparator comp = new TupleComparator(keySchema, sortSpec);
    BSTIndex bst = new BSTIndex(conf);
    BSTIndex.BSTIndexReader reader = bst.getIndexReader(new Path(workDir, "output/index"),
        keySchema, comp);
    reader.open();
    FileScanner scanner = (FileScanner) sm.getLocalScanner(
        new Path(workDir, "output"), "output");

    int cnt = 0;
    while(scanner.next() != null) {
      cnt++;
    }
    scanner.reset();

    assertEquals(100 ,cnt);

    Tuple keytuple = new VTuple(1);
    for(int i = 1 ; i < 100 ; i ++) {
      keytuple.put(0, DatumFactory.createInt(i));
      long offsets = reader.find(keytuple);
      scanner.seek(offsets);
      tuple = scanner.next();
      assertTrue("[seek check " + (i) + " ]" , ("name_" + i).equals(tuple.get(0).asChars()));
      assertTrue("[seek check " + (i) + " ]" , i == tuple.get(1).asInt());
    }


    // The below is for testing RangeRetrieverHandler.
    RangeRetrieverHandler handler = new RangeRetrieverHandler(
        new File(new Path(workDir, "output").toUri()), keySchema, comp);
    Map<String,List<String>> kvs = Maps.newHashMap();
    Tuple startTuple = new VTuple(1);
    startTuple.put(0, DatumFactory.createInt(50));
    kvs.put("start", Lists.newArrayList(
        new String(Base64.encodeBase64(
            RowStoreUtil.RowStoreEncoder.toBytes(keySchema, startTuple), false))));
    Tuple endTuple = new VTuple(1);
    endTuple.put(0, DatumFactory.createInt(80));
    kvs.put("end", Lists.newArrayList(
        new String(Base64.encodeBase64(
            RowStoreUtil.RowStoreEncoder.toBytes(keySchema, endTuple), false))));
    FileChunk chunk = handler.get(kvs);

    scanner.seek(chunk.startOffset());
    keytuple = scanner.next();
    assertEquals(50, keytuple.get(1).asInt());

    long endOffset = chunk.startOffset() + chunk.length();
    while((keytuple = scanner.next()) != null && scanner.getNextOffset() <= endOffset) {
      assertTrue(keytuple.get(1).asInt() <= 80);
    }

    scanner.close();
  }
}