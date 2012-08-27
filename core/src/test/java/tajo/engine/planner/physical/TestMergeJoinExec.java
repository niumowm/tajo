package tajo.engine.planner.physical;

import org.apache.hadoop.conf.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tajo.catalog.*;
import tajo.catalog.proto.CatalogProtos.DataType;
import tajo.catalog.proto.CatalogProtos.StoreType;
import tajo.datum.Datum;
import tajo.datum.DatumFactory;
import tajo.engine.TajoTestingUtility;
import tajo.engine.SubqueryContext;
import tajo.engine.ipc.protocolrecords.Fragment;
import tajo.engine.parser.QueryAnalyzer;
import tajo.engine.parser.QueryBlock;
import tajo.engine.planner.LogicalPlanner;
import tajo.engine.planner.PhysicalPlanner;
import tajo.engine.planner.logical.LogicalNode;
import tajo.engine.utils.TUtil;
import tajo.storage.Appender;
import tajo.storage.StorageManager;
import tajo.storage.Tuple;
import tajo.storage.VTuple;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestMergeJoinExec {
  private Configuration conf;
  private final String TEST_PATH = "target/test-data/TestMergeJoinExec";
  private TajoTestingUtility util;
  private CatalogService catalog;
  private QueryAnalyzer analyzer;
  private SubqueryContext.Factory factory;
  private StorageManager sm;

  @Before
  public void setUp() throws Exception {
    util = new TajoTestingUtility();
    util.initTestDir();
    util.startMiniZKCluster();
    catalog = util.startCatalogCluster().getCatalog();
    File testDir = TajoTestingUtility.getTestDir(TEST_PATH);
    conf = util.getConfiguration();
    sm = StorageManager.get(conf, testDir.getAbsolutePath());

    Schema employeeSchema = new Schema();
    employeeSchema.addColumn("managerId", DataType.INT);
    employeeSchema.addColumn("empId", DataType.INT);
    employeeSchema.addColumn("memId", DataType.INT);
    employeeSchema.addColumn("deptName", DataType.STRING);

    TableMeta employeeMeta = TCatUtil.newTableMeta(employeeSchema,
        StoreType.CSV);
    sm.initTableBase(employeeMeta, "employee");
    Appender appender = sm.getAppender(employeeMeta, "employee", "employee");
    Tuple tuple = new VTuple(employeeMeta.getSchema().getColumnNum());
    for (int i = 0; i < 10; i++) {
      tuple.put(new Datum[] { DatumFactory.createInt(i),
          DatumFactory.createInt(i), DatumFactory.createInt(10 + i),
          DatumFactory.createString("dept_" + i) });
      appender.addTuple(tuple);
    }
    for (int i = 11; i < 20; i+=2) {
      tuple.put(new Datum[] { DatumFactory.createInt(i),
          DatumFactory.createInt(i), DatumFactory.createInt(10 + i),
          DatumFactory.createString("dept_" + i) });
      appender.addTuple(tuple);
    }

    appender.flush();
    appender.close();
    TableDesc employee = TCatUtil.newTableDesc("employee", employeeMeta,
        sm.getTablePath("people"));
    catalog.addTable(employee);

    Schema peopleSchema = new Schema();
    peopleSchema.addColumn("empId", DataType.INT);
    peopleSchema.addColumn("fk_memId", DataType.INT);
    peopleSchema.addColumn("name", DataType.STRING);
    peopleSchema.addColumn("age", DataType.INT);
    TableMeta peopleMeta = TCatUtil.newTableMeta(peopleSchema, StoreType.CSV);
    sm.initTableBase(peopleMeta, "people");
    appender = sm.getAppender(peopleMeta, "people", "people");
    tuple = new VTuple(peopleMeta.getSchema().getColumnNum());
    for (int i = 1; i < 10; i += 2) {
      tuple.put(new Datum[] { DatumFactory.createInt(i),
          DatumFactory.createInt(10 + i),
          DatumFactory.createString("name_" + i),
          DatumFactory.createInt(30 + i) });
      appender.addTuple(tuple);
    }
    for (int i = 10; i < 20; i++) {
      tuple.put(new Datum[] { DatumFactory.createInt(i),
          DatumFactory.createInt(10 + i),
          DatumFactory.createString("name_" + i),
          DatumFactory.createInt(30 + i) });
      appender.addTuple(tuple);
    }

    appender.flush();
    appender.close();

    TableDesc people = TCatUtil.newTableDesc("people", peopleMeta,
        sm.getTablePath("people"));
    catalog.addTable(people);
    analyzer = new QueryAnalyzer(catalog);
  }

  @After
  public void tearDown() throws Exception {
    util.shutdownCatalogCluster();
    util.shutdownMiniZKCluster();
  }

  String[] QUERIES = { "select managerId, e.empId, deptName, e.memId from employee as e inner join people as p on e.empId = p.empId and e.memId = p.fk_memId" };

  @Test
  public final void testInnerJoin() throws IOException {
    Fragment[] empFrags = sm.split("employee");
    Fragment[] peopleFrags = sm.split("people");

    Fragment[] merged = TUtil.concat(empFrags, peopleFrags);

    factory = new SubqueryContext.Factory();
    File workDir = TajoTestingUtility.getTestDir("InnerJoin");
    SubqueryContext ctx = factory.create(TUtil.newQueryUnitAttemptId(),
        merged, workDir);
    QueryBlock query = (QueryBlock) analyzer.parse(ctx, QUERIES[0]);
    LogicalNode plan = LogicalPlanner.createPlan(ctx, query);

    PhysicalPlanner phyPlanner = new PhysicalPlanner(conf,sm);
    PhysicalExec exec = phyPlanner.createPlan(ctx, plan);

    /*
    ProjectionExec proj = (ProjectionExec) exec;
    NLJoinExec nestedLoopJoin = (NLJoinExec) proj.getChild();
    SeqScanExec outerScan = (SeqScanExec) nestedLoopJoin.getOuter();
    SeqScanExec innerScan = (SeqScanExec) nestedLoopJoin.getInner();

    QueryBlock.SortSpec[] outerSortKeys = new QueryBlock.SortSpec[2];
    QueryBlock.SortSpec[] innerSortKeys = new QueryBlock.SortSpec[2];

    Schema employeeSchema = catalog.getTableDesc("employee").getMeta()
        .getSchema();
    outerSortKeys[0] = new QueryBlock.SortSpec(
        employeeSchema.getColumnByName("empId"));
    outerSortKeys[1] = new QueryBlock.SortSpec(
        employeeSchema.getColumnByName("memId"));
    SortNode outerSort = new SortNode(outerSortKeys);
    outerSort.setInputSchema(outerScan.getSchema());
    outerSort.setOutputSchema(outerScan.getSchema());

    Schema peopleSchema = catalog.getTableDesc("people").getMeta().getSchema();
    innerSortKeys[0] = new QueryBlock.SortSpec(
        peopleSchema.getColumnByName("empId"));
    innerSortKeys[1] = new QueryBlock.SortSpec(
        peopleSchema.getColumnByName("fk_memId"));
    SortNode innerSort = new SortNode(innerSortKeys);
    innerSort.setInputSchema(innerScan.getSchema());
    innerSort.setOutputSchema(innerScan.getSchema());

    SortExec outerSortExec = new SortExec(outerSort, outerScan);
    SortExec innerSortExec = new SortExec(innerSort, innerScan);

    MergeJoinExec mergeJoin = new MergeJoinExec(ctx,
        nestedLoopJoin.getJoinNode(), outerSortExec, innerSortExec, outerSortKeys,
        innerSortKeys);
    proj.setChild(mergeJoin);
    exec = proj;*/

    Tuple tuple;
    int count = 0;
    int i = 1;
    while ((tuple = exec.next()) != null) {
      count++;
      assertTrue(i == tuple.getInt(0).asInt());
      assertTrue(i == tuple.getInt(1).asInt());
      assertTrue(("dept_" + i).equals(tuple.getString(2).asChars()));
      assertTrue(10 + i == tuple.getInt(3).asInt());

      i += 2;
    }
    assertEquals(10, count); // expected 10 * 5
  }
}