/*
 * Copyright 2022 HEAVY.AI, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mapd.calcite.parser;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.mapd.parser.server.ExtensionFunction;

import org.apache.calcite.rel.metadata.RelColumnMapping;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeFactory.FieldInfoBuilder;
import org.apache.calcite.schema.FunctionParameter;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.SqlTableFunction;
import org.apache.calcite.sql.fun.SqlArrayValueConstructor;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SameOperandTypeChecker;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeTransforms;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.sql.util.ListSqlOperatorTable;
import org.apache.calcite.sql.util.ReflectiveSqlOperatorTable;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.util.Optionality;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

class CaseInsensitiveListSqlOperatorTable extends ListSqlOperatorTable {
  @Override
  public void lookupOperatorOverloads(SqlIdentifier opName,
          SqlFunctionCategory category,
          SqlSyntax syntax,
          List<SqlOperator> operatorList,
          SqlNameMatcher nameMatcher) {
    for (SqlOperator operator : this.getOperatorList()) {
      if (operator.getSyntax() != syntax) {
        continue;
      }
      if (!opName.isSimple()
              || !nameMatcher.matches(operator.getName(), opName.getSimple())) {
        continue;
      }
      SqlFunctionCategory functionCategory;
      if (operator instanceof SqlFunction) {
        functionCategory = ((SqlFunction) operator).getFunctionType();
      } else {
        functionCategory = SqlFunctionCategory.SYSTEM;
      }
      if (category != functionCategory
              && category != SqlFunctionCategory.USER_DEFINED_FUNCTION) {
        continue;
      }
      operatorList.add(operator);
    }
  }
}

/**
 *
 * @author michael
 */
public class HeavyDBSqlOperatorTable extends ChainedSqlOperatorTable {
  public static final SqlArrayValueConstructorAllowingEmpty ARRAY_VALUE_CONSTRUCTOR =
          new SqlArrayValueConstructorAllowingEmpty();

  static {
    try {
      // some nasty bit to remove the std APPROX_COUNT_DISTINCT function definition
      {
        Field f = ReflectiveSqlOperatorTable.class.getDeclaredField(
                "caseSensitiveOperators");
        f.setAccessible(true);
        Multimap operators = (Multimap) f.get(SqlStdOperatorTable.instance());
        for (Iterator i = operators.entries().iterator(); i.hasNext();) {
          Map.Entry entry = (Map.Entry) i.next();
          if (entry.getValue() == SqlStdOperatorTable.APPROX_COUNT_DISTINCT
                  || entry.getValue() == SqlStdOperatorTable.AVG
                  || entry.getValue() == SqlStdOperatorTable.ARRAY_VALUE_CONSTRUCTOR) {
            i.remove();
          }
        }
      }

      {
        Field f = ReflectiveSqlOperatorTable.class.getDeclaredField(
                "caseInsensitiveOperators");
        f.setAccessible(true);
        Multimap operators = (Multimap) f.get(SqlStdOperatorTable.instance());
        for (Iterator i = operators.entries().iterator(); i.hasNext();) {
          Map.Entry entry = (Map.Entry) i.next();
          if (entry.getValue() == SqlStdOperatorTable.APPROX_COUNT_DISTINCT
                  || entry.getValue() == SqlStdOperatorTable.AVG
                  || entry.getValue() == SqlStdOperatorTable.ARRAY_VALUE_CONSTRUCTOR) {
            i.remove();
          }
        }
      }

      SqlStdOperatorTable.instance().register(ARRAY_VALUE_CONSTRUCTOR);

    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // register our approx count distinct against std table
    // SqlStdOperatorTable.instance().register(new ApproxCountDistinct());
  }

  final static Logger HEAVYDBLOGGER =
          LoggerFactory.getLogger(HeavyDBSqlOperatorTable.class);

  /**
   * Mock operator table for testing purposes. Contains the standard SQL operator
   * table, plus a list of operators.
   */
  // ~ Instance fields --------------------------------------------------------
  private final ListSqlOperatorTable listOpTab;

  // ~ Constructors -----------------------------------------------------------
  public HeavyDBSqlOperatorTable(SqlOperatorTable parentTable) {
    super(ImmutableList.of(parentTable, new CaseInsensitiveListSqlOperatorTable()));
    listOpTab = (ListSqlOperatorTable) tableList.get(1);
  }

  /**
   * Adds an operator to this table.
   *
   * @param op
   */
  public void addOperator(SqlOperator op) {
    listOpTab.add(op);
  }

  public static void addUDF(
          HeavyDBSqlOperatorTable opTab, final Map<String, ExtensionFunction> extSigs) {
    // Don't use anonymous inner classes. They can't be instantiated
    // using reflection when we are deserializing from JSON.
    // opTab.addOperator(new RampFunction());
    // opTab.addOperator(new DedupFunction());
    opTab.addOperator(new MyUDFFunction());
    opTab.addOperator(new PgUnnest());
    opTab.addOperator(new Any());
    opTab.addOperator(new All());
    opTab.addOperator(new Now());
    opTab.addOperator(new Datetime());
    opTab.addOperator(new PgExtract());
    opTab.addOperator(new Dateadd());
    opTab.addOperator(new Datediff());
    opTab.addOperator(new Datepart());
    opTab.addOperator(new PgDateTrunc());
    opTab.addOperator(new Length());
    opTab.addOperator(new CharLength());
    opTab.addOperator(new KeyForString());
    opTab.addOperator(new SampleRatio());
    opTab.addOperator(new WidthBucket());
    opTab.addOperator(new ArrayLength());
    opTab.addOperator(new PgILike());
    opTab.addOperator(new LTrim());
    opTab.addOperator(new RTrim());
    opTab.addOperator(new LPad());
    opTab.addOperator(new RPad());
    opTab.addOperator(new Replace());
    opTab.addOperator(new Reverse());
    opTab.addOperator(new Repeat());
    opTab.addOperator(new SplitPart());
    opTab.addOperator(new RegexpLike());
    opTab.addOperator(new RegexpReplace());
    opTab.addOperator(new RegexpSubstr());
    opTab.addOperator(new RegexpMatch());
    opTab.addOperator(new Likely());
    opTab.addOperator(new Unlikely());
    opTab.addOperator(new Sign());
    opTab.addOperator(new Truncate());
    opTab.addOperator(new ST_IsEmpty());
    opTab.addOperator(new ST_IsValid());
    opTab.addOperator(new ST_Contains());
    opTab.addOperator(new ST_Equals());
    opTab.addOperator(new ST_Intersects());
    opTab.addOperator(new ST_Overlaps());
    opTab.addOperator(new ST_Approx_Overlaps());
    opTab.addOperator(new ST_Disjoint());
    opTab.addOperator(new ST_Within());
    opTab.addOperator(new ST_DWithin());
    opTab.addOperator(new ST_DFullyWithin());
    opTab.addOperator(new ST_Distance());
    opTab.addOperator(new ST_MaxDistance());
    opTab.addOperator(new ST_GeogFromText());
    opTab.addOperator(new ST_GeomFromText());
    opTab.addOperator(new ST_Transform());
    opTab.addOperator(new ST_X());
    opTab.addOperator(new ST_Y());
    opTab.addOperator(new ST_XMin());
    opTab.addOperator(new ST_XMax());
    opTab.addOperator(new ST_YMin());
    opTab.addOperator(new ST_YMax());
    opTab.addOperator(new ST_PointN());
    opTab.addOperator(new ST_StartPoint());
    opTab.addOperator(new ST_EndPoint());
    opTab.addOperator(new ST_Length());
    opTab.addOperator(new ST_Perimeter());
    opTab.addOperator(new ST_Area());
    opTab.addOperator(new ST_NPoints());
    opTab.addOperator(new ST_NRings());
    opTab.addOperator(new ST_SRID());
    opTab.addOperator(new ST_SetSRID());
    opTab.addOperator(new ST_Point());
    opTab.addOperator(new ST_Centroid());
    opTab.addOperator(new ST_Buffer());
    opTab.addOperator(new ST_Intersection());
    opTab.addOperator(new ST_Union());
    opTab.addOperator(new ST_Difference());
    opTab.addOperator(new CastToGeography());
    opTab.addOperator(new EncodeText());
    opTab.addOperator(new OffsetInFragment());
    opTab.addOperator(new ApproxCountDistinct());
    opTab.addOperator(new ApproxMedian());
    opTab.addOperator(new ApproxPercentile());
    opTab.addOperator(new ApproxQuantile());
    opTab.addOperator(new MapDAvg());
    opTab.addOperator(new Sample());
    opTab.addOperator(new LastSample());
    opTab.addOperator(new HeavyDB_Geo_PolyBoundsPtr());
    opTab.addOperator(new HeavyDB_Geo_PolyRenderGroup());
    opTab.addOperator(new convert_meters_to_pixel_width());
    opTab.addOperator(new convert_meters_to_pixel_height());
    opTab.addOperator(new is_point_in_view());
    opTab.addOperator(new is_point_size_in_view());
    opTab.addOperator(new usTimestamp());
    opTab.addOperator(new nsTimestamp());
    if (extSigs == null) {
      return;
    }
    HashSet<String> demangledNames = new HashSet<String>();
    for (Map.Entry<String, ExtensionFunction> extSig : extSigs.entrySet()) {
      final String demangledName = dropSuffix(extSig.getKey());
      final String demangledNameArity =
              String.format("%s-%d", demangledName, extSig.getValue().getArgs().size());
      if (demangledNames.contains(demangledNameArity)) {
        continue;
      }
      demangledNames.add(demangledNameArity);
      if (extSig.getValue().isRowUdf()) {
        opTab.addOperator(new ExtFunction(demangledName, extSig.getValue()));
      } else {
        opTab.addOperator(new ExtTableFunction(demangledName, extSig.getValue()));
      }
    }
  }

  private static String dropSuffix(final String str) {
    int suffix_idx = str.indexOf("__");
    if (suffix_idx == -1) {
      return str;
    }
    assert suffix_idx > 0;
    return str.substring(0, suffix_idx);
  }

  //@Deprecated // to be removed before 2.0
  // public static final SqlFunction LTRIM = SqlLibraryOperators.LTRIM;

  //@Deprecated // to be removed before 2.0
  // public static final SqlFunction RTRIM = SqlLibraryOperators.RTRIM;

  public static class SqlArrayValueConstructorAllowingEmpty
          extends SqlArrayValueConstructor {
    @Override
    protected RelDataType getComponentType(
            RelDataTypeFactory typeFactory, List<RelDataType> argTypes) {
      if (argTypes.isEmpty()) {
        return typeFactory.createSqlType(SqlTypeName.NULL);
      }
      return super.getComponentType(typeFactory, argTypes);
    }

    @Override
    public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
      if (callBinding.operands().isEmpty()) {
        return true;
      }
      return super.checkOperandTypes(callBinding, throwOnFailure);
    }
  }

  /**
   * "RAMP" user-defined function.
   */
  public static class RampFunction extends SqlFunction {
    public RampFunction() {
      super("RAMP",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.NUMERIC,
              SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.builder().add("I", SqlTypeName.INTEGER).build();
    }
  }

  /**
   * "DEDUP" user-defined function.
   */
  public static class DedupFunction extends SqlFunction {
    public DedupFunction() {
      super("DEDUP",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.VARIADIC,
              SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.builder().add("NAME", SqlTypeName.VARCHAR, 1024).build();
    }
  }

  /**
   * "MyUDFFunction" user-defined function test. our udf's will look like system
   * functions to calcite as it has no access to the code
   */
  public static class MyUDFFunction extends SqlFunction {
    public MyUDFFunction() {
      super("MyUDF",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.STRING_STRING,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BIGINT);
    }
  }

  /* Postgres-style UNNEST */
  public static class PgUnnest extends SqlFunction {
    public PgUnnest() {
      super("PG_UNNEST",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.ARRAY,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      RelDataType elem_type = opBinding.getOperandType(0).getComponentType();
      assert elem_type != null;
      return elem_type;
    }
  }

  /* ANY qualifier */
  public static class Any extends SqlFunction {
    public Any() {
      super("PG_ANY",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.ARRAY,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      RelDataType elem_type = opBinding.getOperandType(0).getComponentType();
      assert elem_type != null;
      return elem_type;
    }
  }

  /* ALL qualifier */
  public static class All extends SqlFunction {
    public All() {
      super("PG_ALL",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.ARRAY,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      RelDataType elem_type = opBinding.getOperandType(0).getComponentType();
      assert elem_type != null;
      return elem_type;
    }
  }

  /* NOW() */
  public static class Now extends SqlFunction {
    public Now() {
      super("NOW",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.NILADIC,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 0;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.TIMESTAMP);
    }
  }

  /* DATETIME */
  public static class Datetime extends SqlFunction {
    public Datetime() {
      super("DATETIME",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.STRING,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(
              SqlTypeName.TIMESTAMP, opBinding.getOperandType(0).getPrecision());
    }
  }

  /* Postgres-style EXTRACT */
  public static class PgExtract extends SqlFunction {
    public PgExtract() {
      super("PG_EXTRACT",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.STRING, SqlTypeFamily.DATETIME),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.BIGINT),
              opBinding.getOperandType(1).isNullable());
    }
  }

  public static class Datepart extends SqlFunction {
    public Datepart() {
      super("DATEPART",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.STRING, SqlTypeFamily.DATETIME),
              SqlFunctionCategory.TIMEDATE);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.BIGINT),
              opBinding.getOperandType(1).isNullable());
    }
  }

  public static class Dateadd extends SqlFunction {
    public Dateadd() {
      super("DATEADD",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.STRING,
                      SqlTypeFamily.INTEGER,
                      SqlTypeFamily.DATETIME),
              SqlFunctionCategory.TIMEDATE);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(
                      SqlTypeName.TIMESTAMP, opBinding.getOperandType(2).getPrecision()),
              opBinding.getOperandType(2).isNullable());
    }
  }

  public static class Datediff extends SqlFunction {
    public Datediff() {
      super("DATEDIFF",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.STRING,
                      SqlTypeFamily.DATETIME,
                      SqlTypeFamily.DATETIME),
              SqlFunctionCategory.TIMEDATE);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.BIGINT),
              opBinding.getOperandType(1).isNullable()
                      || opBinding.getOperandType(2).isNullable());
    }
  }

  /* Postgres-style DATE_TRUNC */
  public static class PgDateTrunc extends SqlFunction {
    public PgDateTrunc() {
      super("PG_DATE_TRUNC",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.STRING, SqlTypeFamily.DATETIME),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(
                      SqlTypeName.TIMESTAMP, opBinding.getOperandType(1).getPrecision()),
              opBinding.getOperandType(1).isNullable());
    }
  }

  public static class Length extends SqlFunction {
    public Length() {
      super("LENGTH",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.STRING,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }
  }

  public static class CharLength extends SqlFunction {
    public CharLength() {
      super("CHAR_LENGTH",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.STRING,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }
  }

  public static class KeyForString extends SqlFunction {
    public KeyForString() {
      super("KEY_FOR_STRING",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.STRING,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.INTEGER),
              opBinding.getOperandType(0).isNullable());
    }
  }

  public static class SampleRatio extends SqlFunction {
    public SampleRatio() {
      super("SAMPLE_RATIO",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.ArrayList<SqlTypeFamily> families =
              new java.util.ArrayList<SqlTypeFamily>();
      families.add(SqlTypeFamily.NUMERIC);
      return families;
    }
  }

  public static class WidthBucket extends SqlFunction {
    public WidthBucket() {
      super("WIDTH_BUCKET",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.INTEGER), /*nullable=*/true);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.ArrayList<SqlTypeFamily> families =
              new java.util.ArrayList<SqlTypeFamily>();
      families.add(SqlTypeFamily.NUMERIC);
      families.add(SqlTypeFamily.NUMERIC);
      families.add(SqlTypeFamily.NUMERIC);
      families.add(SqlTypeFamily.INTEGER);
      return families;
    }
  }

  public static class ArrayLength extends SqlFunction {
    public ArrayLength() {
      super("ARRAY_LENGTH",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.ARRAY,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }
  }

  public static class PgILike extends SqlFunction {
    public PgILike() {
      super("PG_ILIKE",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(getSignatureFamilies(), new EscapeOptional()),
              SqlFunctionCategory.SYSTEM);
    }

    private static java.util.List<SqlTypeFamily> getSignatureFamilies() {
      java.util.ArrayList<SqlTypeFamily> families =
              new java.util.ArrayList<SqlTypeFamily>();
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.STRING);
      return families;
    }

    private static class EscapeOptional
            implements java.util.function.Predicate<Integer>, Predicate<Integer> {
      @Override
      public boolean test(Integer t) {
        return apply(t);
      }

      @Override
      public boolean apply(Integer t) {
        return t == 2;
      }
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
    }
  }

  public static class RegexpLike extends SqlFunction {
    public RegexpLike() {
      super("REGEXP_LIKE",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(getSignatureFamilies(), new EscapeOptional()),
              SqlFunctionCategory.SYSTEM);
    }

    private static java.util.List<SqlTypeFamily> getSignatureFamilies() {
      java.util.ArrayList<SqlTypeFamily> families =
              new java.util.ArrayList<SqlTypeFamily>();
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.STRING);
      return families;
    }

    private static class EscapeOptional
            implements java.util.function.Predicate<Integer>, Predicate<Integer> {
      @Override
      public boolean test(Integer t) {
        return apply(t);
      }

      public boolean apply(Integer t) {
        return t == 2;
      }
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
    }
  }

  public static class LeftRightTrim extends SqlFunction {
    public LeftRightTrim(final String name, final SqlKind kind) {
      super(name,
              kind,
              ReturnTypes.ARG0.andThen(SqlTypeTransforms.TO_NULLABLE)
                      .andThen(SqlTypeTransforms.TO_VARYING),
              null,
              OperandTypes.and(
                      OperandTypes.family(SqlTypeFamily.STRING, SqlTypeFamily.STRING),
                      new SameOperandTypeChecker(2) {
                        @Override
                        protected List<Integer> getOperandList(int operandCount) {
                          return ImmutableList.of(0, 1);
                        }
                      }),
              SqlFunctionCategory.STRING);
    }

    @Override
    public SqlCall createCall(@Nullable SqlLiteral functionQualifier,
            SqlParserPos pos,
            @Nullable SqlNode... operands) {
      assert functionQualifier == null;
      switch (operands.length) {
        case 1:
          operands = new SqlNode[] {SqlLiteral.createCharString(" ", pos), operands[0]};
          break;
        case 2:
          if (operands[1] == null) {
            operands[1] = SqlLiteral.createCharString(" ", pos);
          }
          operands = new SqlNode[] {operands[1], operands[0]};
          break;
        default:
          throw new IllegalArgumentException(
                  "Invalid operand count " + Arrays.toString(operands));
      }
      return super.createCall(functionQualifier, pos, operands);
    }
  }

  public static class LTrim extends LeftRightTrim {
    public LTrim() {
      super("LTRIM", SqlKind.LTRIM);
    }
  }
  public static class RTrim extends LeftRightTrim {
    public RTrim() {
      super("RTRIM", SqlKind.RTRIM);
    }
  }
  public static class LeftRightPad extends SqlFunction {
    public LeftRightPad(final String name) {
      super(name,
              SqlKind.OTHER_FUNCTION,
              ReturnTypes.ARG0.andThen(SqlTypeTransforms.TO_NULLABLE)
                      .andThen(SqlTypeTransforms.TO_VARYING),
              null,
              OperandTypes.and(OperandTypes.family(SqlTypeFamily.STRING,
                                       SqlTypeFamily.INTEGER,
                                       SqlTypeFamily.STRING),
                      new SameOperandTypeChecker(3) {
                        @Override
                        protected List<Integer> getOperandList(int operandCount) {
                          return ImmutableList.of(0, 2);
                        }
                      }),
              SqlFunctionCategory.STRING);
    }

    @Override
    public SqlCall createCall(@Nullable SqlLiteral functionQualifier,
            SqlParserPos pos,
            @Nullable SqlNode... operands) {
      assert functionQualifier == null;
      switch (operands.length) {
        case 2:
          operands = new SqlNode[] {
                  operands[0], operands[1], SqlLiteral.createCharString(" ", pos)};
          break;
        case 3:
          if (operands[2] == null) {
            operands[2] = SqlLiteral.createCharString(" ", pos);
          }
          operands = new SqlNode[] {operands[0], operands[1], operands[2]};
          break;
        default:
          throw new IllegalArgumentException(
                  "Invalid operand count " + Arrays.toString(operands));
      }
      return super.createCall(functionQualifier, pos, operands);
    }

    @Override
    public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
      if (!super.checkOperandTypes(callBinding, throwOnFailure)) {
        return false;
      }
      switch (kind) {
        case TRIM:
          return SqlTypeUtil.isCharTypeComparable(callBinding,
                  ImmutableList.of(callBinding.operand(0), callBinding.operand(2)),
                  throwOnFailure);
        default:
          return true;
      }
    }
  }

  public static class LPad extends LeftRightPad {
    public LPad() {
      super("LPAD");
    }
  }
  public static class RPad extends LeftRightPad {
    public RPad() {
      super("RPAD");
    }
  }

  public static class SplitPart extends SqlFunction {
    public SplitPart() {
      super("SPLIT_PART",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(getSignatureFamilies()),
              SqlFunctionCategory.STRING);
    }

    private static java.util.List<SqlTypeFamily> getSignatureFamilies() {
      java.util.ArrayList<SqlTypeFamily> families =
              new java.util.ArrayList<SqlTypeFamily>();
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.INTEGER);
      return families;
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      return opBinding.getOperandType(0);
    }
  }

  public static class Replace extends SqlFunction {
    public Replace() {
      super("REPLACE",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(getSignatureFamilies()),
              SqlFunctionCategory.STRING);
    }

    private static java.util.List<SqlTypeFamily> getSignatureFamilies() {
      java.util.ArrayList<SqlTypeFamily> families =
              new java.util.ArrayList<SqlTypeFamily>();
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.STRING);
      return families;
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      return opBinding.getOperandType(0);
    }

    @Override
    public SqlCall createCall(@Nullable SqlLiteral functionQualifier,
            SqlParserPos pos,
            @Nullable SqlNode... operands) {
      assert functionQualifier == null;
      switch (operands.length) {
        case 2:
          operands = new SqlNode[] {
                  operands[0], operands[1], SqlLiteral.createCharString("", pos)};
          break;
        case 3:
          break;
        default:
          throw new IllegalArgumentException(
                  "Invalid operand count " + Arrays.toString(operands));
      }
      return super.createCall(functionQualifier, pos, operands);
    }
  }
  public static class Reverse extends SqlFunction {
    public Reverse() {
      super("REVERSE",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(getSignatureFamilies()),
              SqlFunctionCategory.STRING);
    }

    private static java.util.List<SqlTypeFamily> getSignatureFamilies() {
      java.util.ArrayList<SqlTypeFamily> families =
              new java.util.ArrayList<SqlTypeFamily>();
      families.add(SqlTypeFamily.STRING);
      return families;
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      return opBinding.getOperandType(0);
    }
  }
  public static class Repeat extends SqlFunction {
    public Repeat() {
      super("REPEAT",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(getSignatureFamilies()),
              SqlFunctionCategory.STRING);
    }

    private static java.util.List<SqlTypeFamily> getSignatureFamilies() {
      java.util.ArrayList<SqlTypeFamily> families =
              new java.util.ArrayList<SqlTypeFamily>();
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.INTEGER);
      return families;
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      return opBinding.getOperandType(0);
    }
  }

  public static class RegexpReplace extends SqlFunction {
    public RegexpReplace() {
      super("REGEXP_REPLACE",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(getSignatureFamilies()),
              SqlFunctionCategory.STRING);
    }

    private static java.util.List<SqlTypeFamily> getSignatureFamilies() {
      java.util.ArrayList<SqlTypeFamily> families =
              new java.util.ArrayList<SqlTypeFamily>();
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.INTEGER);
      families.add(SqlTypeFamily.INTEGER);
      families.add(SqlTypeFamily.STRING);
      return families;
    }

    @Override
    public SqlCall createCall(@Nullable SqlLiteral functionQualifier,
            SqlParserPos pos,
            @Nullable SqlNode... operands) {
      assert functionQualifier == null;
      final int num_operands = operands.length;
      if (num_operands < 2 || num_operands > 6) {
        throw new IllegalArgumentException(
                "Invalid operand count " + Arrays.toString(operands));
      }
      SqlNode[] new_operands = new SqlNode[6];
      // operand string
      new_operands[0] = operands[0];
      // pattern
      new_operands[1] = operands[1];
      // replacement
      if (num_operands < 3 || operands[2] == null) {
        new_operands[2] = SqlLiteral.createCharString("", pos);
      } else {
        new_operands[2] = operands[2];
      }
      // position
      if (num_operands < 4 || operands[3] == null) {
        new_operands[3] = SqlLiteral.createExactNumeric("1", pos);
      } else {
        new_operands[3] = operands[3];
      }
      // occurrence
      if (num_operands < 5 || operands[4] == null) {
        new_operands[4] = SqlLiteral.createExactNumeric("0", pos);
      } else {
        new_operands[4] = operands[4];
      }
      // parameters
      if (num_operands < 6 || operands[5] == null) {
        new_operands[5] = SqlLiteral.createCharString("c", pos);
      } else {
        new_operands[5] = operands[5];
      }
      return super.createCall(functionQualifier, pos, new_operands);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      return opBinding.getOperandType(0);
    }
  }

  public static class RegexpSubstr extends SqlFunction {
    public RegexpSubstr() {
      super("REGEXP_SUBSTR",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(getSignatureFamilies()),
              SqlFunctionCategory.STRING);
    }

    public RegexpSubstr(final String alias) {
      super(alias,
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(getSignatureFamilies()),
              SqlFunctionCategory.SYSTEM);
    }

    private static java.util.List<SqlTypeFamily> getSignatureFamilies() {
      java.util.ArrayList<SqlTypeFamily> families =
              new java.util.ArrayList<SqlTypeFamily>();
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.INTEGER);
      families.add(SqlTypeFamily.INTEGER);
      families.add(SqlTypeFamily.STRING);
      families.add(SqlTypeFamily.INTEGER);
      return families;
    }

    @Override
    public SqlCall createCall(@Nullable SqlLiteral functionQualifier,
            SqlParserPos pos,
            @Nullable SqlNode... operands) {
      assert functionQualifier == null;
      final int num_operands = operands.length;
      if (num_operands < 2 || num_operands > 6) {
        throw new IllegalArgumentException(
                "Invalid operand count " + Arrays.toString(operands));
      }
      SqlNode[] new_operands = new SqlNode[6];

      // operand string (required)
      new_operands[0] = operands[0];
      // pattern (required)
      new_operands[1] = operands[1];
      // start position
      if (num_operands < 3 || operands[2] == null) {
        new_operands[2] = SqlLiteral.createExactNumeric("1", pos);
      } else {
        new_operands[2] = operands[2];
      }
      // match occurrence
      if (num_operands < 4 || operands[3] == null) {
        new_operands[3] = SqlLiteral.createExactNumeric("1", pos);
      } else {
        new_operands[3] = operands[3];
      }
      // regex params (default 'c' = case sensitive)
      if (num_operands < 5 || operands[4] == null) {
        new_operands[4] = SqlLiteral.createCharString("c", pos);
      } else {
        new_operands[4] = operands[4];
      }
      // Sub-match occurrence, valid with regex param 'e'
      if (num_operands < 6 || operands[5] == null) {
        new_operands[5] = SqlLiteral.createExactNumeric("1", pos);
      } else {
        new_operands[5] = operands[5];
      }
      return super.createCall(functionQualifier, pos, new_operands);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      return opBinding.getOperandType(0);
    }
  }

  public static class RegexpMatch extends RegexpSubstr {
    public RegexpMatch() {
      super("REGEXP_MATCH");
    }
  }

  public static class Likely extends SqlFunction {
    public Likely() {
      super("LIKELY",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.BOOLEAN,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      return opBinding.getOperandType(0);
    }
  }

  public static class Unlikely extends SqlFunction {
    public Unlikely() {
      super("UNLIKELY",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.BOOLEAN,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      return opBinding.getOperandType(0);
    }
  }

  public static class Sign extends SqlFunction {
    public Sign() {
      super("SIGN",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.NUMERIC,
              SqlFunctionCategory.NUMERIC);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      return opBinding.getOperandType(0);
    }
  }

  static class Truncate extends SqlFunction {
    Truncate() {
      super("TRUNCATE",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.NUMERIC);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      return opBinding.getOperandType(0);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> truncate_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      truncate_sig.add(SqlTypeFamily.NUMERIC);
      truncate_sig.add(SqlTypeFamily.INTEGER);
      return truncate_sig;
    }
  }

  static class ST_IsEmpty extends SqlFunction {
    ST_IsEmpty() {
      super("ST_IsEmpty",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_isempty_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_isempty_sig.add(SqlTypeFamily.ANY);
      return st_isempty_sig;
    }
  }

  static class ST_IsValid extends SqlFunction {
    ST_IsValid() {
      super("ST_IsValid",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_isvalid_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_isvalid_sig.add(SqlTypeFamily.ANY);
      return st_isvalid_sig;
    }
  }

  static class ST_Contains extends SqlFunction {
    ST_Contains() {
      super("ST_Contains",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.BOOLEAN),
              opBinding.getOperandType(0).isNullable()
                      || opBinding.getOperandType(1).isNullable());
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_contains_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_contains_sig.add(SqlTypeFamily.ANY);
      st_contains_sig.add(SqlTypeFamily.ANY);
      return st_contains_sig;
    }
  }

  static class ST_Equals extends SqlFunction {
    ST_Equals() {
      super("ST_Equals",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_equals_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_equals_sig.add(SqlTypeFamily.ANY);
      st_equals_sig.add(SqlTypeFamily.ANY);
      return st_equals_sig;
    }
  }

  static class ST_Intersects extends SqlFunction {
    ST_Intersects() {
      super("ST_Intersects",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.BOOLEAN),
              opBinding.getOperandType(0).isNullable()
                      || opBinding.getOperandType(1).isNullable());
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_intersects_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_intersects_sig.add(SqlTypeFamily.ANY);
      st_intersects_sig.add(SqlTypeFamily.ANY);
      return st_intersects_sig;
    }
  }

  static class ST_Overlaps extends SqlFunction {
    ST_Overlaps() {
      super("ST_Overlaps",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_overlaps_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_overlaps_sig.add(SqlTypeFamily.ANY);
      st_overlaps_sig.add(SqlTypeFamily.ANY);
      return st_overlaps_sig;
    }
  }

  static class ST_Approx_Overlaps extends SqlFunction {
    ST_Approx_Overlaps() {
      super("ST_Approx_Overlaps",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_overlaps_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_overlaps_sig.add(SqlTypeFamily.ANY);
      st_overlaps_sig.add(SqlTypeFamily.ANY);
      return st_overlaps_sig;
    }
  }

  static class ST_Disjoint extends SqlFunction {
    ST_Disjoint() {
      super("ST_Disjoint",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.BOOLEAN),
              opBinding.getOperandType(0).isNullable()
                      || opBinding.getOperandType(1).isNullable());
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_disjoint_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_disjoint_sig.add(SqlTypeFamily.ANY);
      st_disjoint_sig.add(SqlTypeFamily.ANY);
      return st_disjoint_sig;
    }
  }

  static class ST_Within extends SqlFunction {
    ST_Within() {
      super("ST_Within",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.BOOLEAN),
              opBinding.getOperandType(0).isNullable()
                      || opBinding.getOperandType(1).isNullable());
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_within_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_within_sig.add(SqlTypeFamily.ANY);
      st_within_sig.add(SqlTypeFamily.ANY);
      return st_within_sig;
    }
  }

  static class ST_DWithin extends SqlFunction {
    ST_DWithin() {
      super("ST_DWithin",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 3;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.BOOLEAN),
              opBinding.getOperandType(0).isNullable()
                      || opBinding.getOperandType(1).isNullable()
                      || opBinding.getOperandType(2).isNullable());
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_dwithin_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_dwithin_sig.add(SqlTypeFamily.ANY);
      st_dwithin_sig.add(SqlTypeFamily.ANY);
      st_dwithin_sig.add(SqlTypeFamily.NUMERIC);
      return st_dwithin_sig;
    }
  }

  static class ST_DFullyWithin extends SqlFunction {
    ST_DFullyWithin() {
      super("ST_DFullyWithin",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 3;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.BOOLEAN),
              opBinding.getOperandType(0).isNullable()
                      || opBinding.getOperandType(1).isNullable()
                      || opBinding.getOperandType(2).isNullable());
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_dwithin_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_dwithin_sig.add(SqlTypeFamily.ANY);
      st_dwithin_sig.add(SqlTypeFamily.ANY);
      st_dwithin_sig.add(SqlTypeFamily.NUMERIC);
      return st_dwithin_sig;
    }
  }

  static class ST_Distance extends SqlFunction {
    ST_Distance() {
      super("ST_Distance",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.DOUBLE),
              opBinding.getOperandType(0).isNullable()
                      || opBinding.getOperandType(1).isNullable());
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_distance_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_distance_sig.add(SqlTypeFamily.ANY);
      st_distance_sig.add(SqlTypeFamily.ANY);
      return st_distance_sig;
    }
  }

  static class ST_MaxDistance extends SqlFunction {
    ST_MaxDistance() {
      super("ST_MaxDistance",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.DOUBLE),
              opBinding.getOperandType(0).isNullable()
                      || opBinding.getOperandType(1).isNullable());
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_maxdistance_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_maxdistance_sig.add(SqlTypeFamily.ANY);
      st_maxdistance_sig.add(SqlTypeFamily.ANY);
      return st_maxdistance_sig;
    }
  }

  static class ST_GeogFromText extends SqlFunction {
    ST_GeogFromText() {
      super("ST_GeogFromText",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.or(OperandTypes.family(SqlTypeFamily.ANY),
                      OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.INTEGER)),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }
  }

  static class ST_GeomFromText extends SqlFunction {
    ST_GeomFromText() {
      super("ST_GeomFromText",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.or(OperandTypes.family(SqlTypeFamily.ANY),
                      OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.INTEGER)),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }
  }

  static class ST_Transform extends SqlFunction {
    ST_Transform() {
      super("ST_Transform",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.INTEGER),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.INTEGER),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_X extends SqlFunction {
    ST_X() {
      super("ST_X",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.DOUBLE),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_Y extends SqlFunction {
    ST_Y() {
      super("ST_Y",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.DOUBLE),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_XMin extends SqlFunction {
    ST_XMin() {
      super("ST_XMin",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.DOUBLE),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_XMax extends SqlFunction {
    ST_XMax() {
      super("ST_XMax",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.DOUBLE),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_YMin extends SqlFunction {
    ST_YMin() {
      super("ST_YMin",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.DOUBLE),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_YMax extends SqlFunction {
    ST_YMax() {
      super("ST_YMax",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.DOUBLE),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_PointN extends SqlFunction {
    ST_PointN() {
      super("ST_PointN",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.INTEGER),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.INTEGER),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_EndPoint extends SqlFunction {
    ST_EndPoint() {
      super("ST_EndPoint",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.INTEGER),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_StartPoint extends SqlFunction {
    ST_StartPoint() {
      super("ST_StartPoint",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.INTEGER),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_Length extends SqlFunction {
    ST_Length() {
      super("ST_Length",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.DOUBLE),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_Perimeter extends SqlFunction {
    ST_Perimeter() {
      super("ST_Perimeter",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.DOUBLE),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_Area extends SqlFunction {
    ST_Area() {
      super("ST_Area",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.DOUBLE),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_NPoints extends SqlFunction {
    ST_NPoints() {
      super("ST_NPoints",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.INTEGER),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_NRings extends SqlFunction {
    ST_NRings() {
      super("ST_NRings",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.INTEGER),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_SRID extends SqlFunction {
    ST_SRID() {
      super("ST_SRID",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.INTEGER),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_SetSRID extends SqlFunction {
    ST_SetSRID() {
      super("ST_SetSRID",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.INTEGER),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.INTEGER),
              opBinding.getOperandType(0).isNullable());
    }
  }

  static class ST_Point extends SqlFunction {
    ST_Point() {
      super("ST_Point",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }
  }

  static class ST_Centroid extends SqlFunction {
    ST_Centroid() {
      super("ST_Centroid",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_centroid_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_centroid_sig.add(SqlTypeFamily.ANY);
      return st_centroid_sig;
    }
  }

  static class ST_Buffer extends SqlFunction {
    ST_Buffer() {
      super("ST_Buffer",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_buffer_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_buffer_sig.add(SqlTypeFamily.ANY);
      st_buffer_sig.add(SqlTypeFamily.NUMERIC);
      return st_buffer_sig;
    }
  }

  static class ST_Intersection extends SqlFunction {
    ST_Intersection() {
      super("ST_Intersection",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_intersection_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_intersection_sig.add(SqlTypeFamily.ANY);
      st_intersection_sig.add(SqlTypeFamily.ANY);
      return st_intersection_sig;
    }
  }

  static class ST_Union extends SqlFunction {
    ST_Union() {
      super("ST_Union",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_union_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_union_sig.add(SqlTypeFamily.ANY);
      st_union_sig.add(SqlTypeFamily.ANY);
      return st_union_sig;
    }
  }

  static class ST_Difference extends SqlFunction {
    ST_Difference() {
      super("ST_Difference",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(signature()),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 2;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }

    private static java.util.List<SqlTypeFamily> signature() {
      java.util.List<SqlTypeFamily> st_difference_sig =
              new java.util.ArrayList<SqlTypeFamily>();
      st_difference_sig.add(SqlTypeFamily.ANY);
      st_difference_sig.add(SqlTypeFamily.ANY);
      return st_difference_sig;
    }
  }

  static class CastToGeography extends SqlFunction {
    CastToGeography() {
      super("CastToGeography",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }
  }

  static class EncodeText extends SqlFunction {
    EncodeText() {
      super("ENCODE_TEXT",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.STRING),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      return opBinding.getOperandType(0);
    }
  }

  /* OFFSET_IN_FRAGMENT() */
  public static class OffsetInFragment extends SqlFunction {
    public OffsetInFragment() {
      super("OFFSET_IN_FRAGMENT",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.NILADIC,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 0;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BIGINT);
    }
  }

  static class ApproxCountDistinct extends SqlAggFunction {
    ApproxCountDistinct() {
      super("APPROX_COUNT_DISTINCT",
              null,
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.or(OperandTypes.family(SqlTypeFamily.ANY),
                      OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.INTEGER)),
              SqlFunctionCategory.SYSTEM,
              false,
              false,
              Optionality.FORBIDDEN);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BIGINT);
    }
  }

  static class ApproxMedian extends SqlAggFunction {
    ApproxMedian() {
      super("APPROX_MEDIAN",
              null,
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.NUMERIC),
              SqlFunctionCategory.SYSTEM,
              false,
              false,
              Optionality.FORBIDDEN);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.DOUBLE);
    }
  }

  static class ApproxPercentile extends SqlAggFunction {
    ApproxPercentile() {
      super("APPROX_PERCENTILE",
              null,
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
              SqlFunctionCategory.SYSTEM,
              false,
              false,
              Optionality.FORBIDDEN);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.DOUBLE);
    }
  }

  static class ApproxQuantile extends SqlAggFunction {
    ApproxQuantile() {
      super("APPROX_QUANTILE",
              null,
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
              SqlFunctionCategory.SYSTEM,
              false,
              false,
              Optionality.FORBIDDEN);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.DOUBLE);
    }
  }

  static class MapDAvg extends SqlAggFunction {
    MapDAvg() {
      super("AVG",
              null,
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.NUMERIC),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.DOUBLE);
    }
  }

  public static class Sample extends SqlAggFunction {
    public Sample() {
      super("SAMPLE",
              null,
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.ANY,
              SqlFunctionCategory.SYSTEM,
              false,
              false,
              Optionality.FORBIDDEN);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      return opBinding.getOperandType(0);
    }
  }

  // for backwards compatibility
  public static class LastSample extends SqlAggFunction {
    public LastSample() {
      super("LAST_SAMPLE",
              null,
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.ANY,
              SqlFunctionCategory.SYSTEM,
              false,
              false,
              Optionality.FORBIDDEN);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      return opBinding.getOperandType(0);
    }
  }

  static class ExtFunction extends SqlFunction {
    ExtFunction(final String name, final ExtensionFunction sig) {
      super(name,
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(sig.toSqlSignature()),
              SqlFunctionCategory.SYSTEM);
      ret = sig.getSqlRet();
      arg_names = sig.getArgNames();
    }

    @Override
    public List<String> getParamNames() {
      return arg_names;
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(typeFactory.createSqlType(ret), true);
    }

    private final SqlTypeName ret;
    private final List<String> arg_names;
  }

  public static class ExtTableFunction extends SqlFunction implements SqlTableFunction {
    ExtTableFunction(final String name, final ExtensionFunction sig) {
      super(name,
              SqlKind.OTHER_FUNCTION,
              ReturnTypes.CURSOR,
              null,
              OperandTypes.family(sig.toSqlSignature()),
              SqlFunctionCategory.USER_DEFINED_TABLE_FUNCTION);
      arg_types = sig.toSqlSignature();
      outs = sig.getSqlOuts();
      out_names = sig.getOutNames();
      arg_names = sig.getArgNames();
      // pretty_arg_names will be same as arg_names, except with
      // arg names for cursors stripped of array elements, i.e
      // my_cursor_input[x, y, z] => my_cursor_input
      pretty_arg_names = sig.getPrettyArgNames();
      options = sig.getOptions();
    }

    // The following method allows for parameter annotation
    // i.e. my_param => 3.
    // Note this method is deprecated, and it appears that
    // getParameters() is the correct method to use, but
    // need to troubleshoot why it's not being called
    @Override
    public List<String> getParamNames() {
      return pretty_arg_names;
    }

    // Per the above, this appears to be the non-deprecated way
    // to allow for parameter annotation, and it also allows
    // for specifying optionality.
    // However it currently is not being picked up/called,
    // so need to troubleshot. Leaving in for now as scaffolding
    // for the correct approach forward.

    // Note that OperandTypeInference seems another route
    // to implementing optionality.

    public List<FunctionParameter> getParameters() {
      final Boolean has_names = this.pretty_arg_names != null
              && this.pretty_arg_names.size() == this.arg_types.size();
      final List<FunctionParameter> parameters = new java.util.ArrayList<>();
      for (int i = 0; i < this.arg_types.size(); i++) {
        final int arg_idx = i;
        parameters.add(new FunctionParameter() {
          public int getOrdinal() {
            return arg_idx;
          }

          public String getName() {
            if (has_names) {
              return pretty_arg_names.get(arg_idx);
            }
            return "arg" + arg_idx;
          }

          public RelDataType getType(RelDataTypeFactory typeFactory) {
            return arg_types.get(arg_idx).getDefaultConcreteType(typeFactory);
          }

          public boolean isOptional() {
            return false;
          }
        });
      }
      return parameters;
    }

    @Override
    public SqlReturnTypeInference getRowTypeInference() {
      return opBinding -> {
        FieldInfoBuilder ret = opBinding.getTypeFactory().builder();
        for (int out_idx = 0; out_idx < outs.size(); ++out_idx) {
          ret = ret.add(out_names.get(out_idx), outs.get(out_idx));
          ret = ret.nullable(true);
        }
        return ret.build();
      };
    }

    private void debugPrint(String msg, Boolean debugMode) {
      if (debugMode) {
        System.out.println(msg);
      }
    }

    public Set<RelColumnMapping> getColumnMappings() {
      final Boolean debugMode = false;
      Set<RelColumnMapping> s = new HashSet<RelColumnMapping>();
      debugPrint("getNameAsId() -> " + getNameAsId() + ", arg_names=" + arg_names
                      + ", out_names=" + out_names,
              debugMode);
      if (Integer.valueOf(options.getOrDefault("filter_table_function_transpose", "0"))
              == 1) {
        debugPrint("getNameAsId() -> " + getNameAsId(), debugMode);
        int rel_idx = -1;
        for (int arg_idx = 0; arg_idx < arg_names.size(); ++arg_idx) {
          String arg_name = arg_names.get(arg_idx);
          String[] fields;
          int start = arg_name.indexOf("[");
          if (start != -1) {
            rel_idx += 1;
            int end = arg_name.lastIndexOf("]");
            fields = arg_name.substring(start + 1, end)
                             .replaceAll("\\s+", "")
                             .split(",", 0);
          } else {
            fields = new String[] {arg_name};
          }
          debugPrint("fields=" + Arrays.toString(fields), debugMode);
          for (int field_idx = 0; field_idx < fields.length; ++field_idx) {
            int out_idx = out_names.indexOf(fields[field_idx]);
            if (out_idx >= 0) {
              s.add(new RelColumnMapping(out_idx, rel_idx, field_idx, false));
              debugPrint("out_idx, arg_idx/rel_idx, field_idx=" + out_idx + ", " + arg_idx
                              + "/" + rel_idx + ", " + field_idx,
                      debugMode);
            }
          }
        }
      }
      return s;
    }

    private final List<SqlTypeFamily> arg_types;
    private final List<SqlTypeName> outs;
    private final List<String> arg_names;
    private final List<String> pretty_arg_names;
    private final List<String> out_names;
    private final Map<String, String> options;
  }

  //
  // Internal accessors for in-situ poly render queries
  //

  static class HeavyDB_Geo_PolyBoundsPtr extends SqlFunction {
    HeavyDB_Geo_PolyBoundsPtr() {
      super("HeavyDB_Geo_PolyBoundsPtr",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BIGINT);
    }
  }

  static class HeavyDB_Geo_PolyRenderGroup extends SqlFunction {
    HeavyDB_Geo_PolyRenderGroup() {
      super("HeavyDB_Geo_PolyRenderGroup",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.INTEGER);
    }
  }

  static class convert_meters_to_pixel_width extends SqlFunction {
    convert_meters_to_pixel_width() {
      super("convert_meters_to_pixel_width",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.ANY,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 6;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.DOUBLE);
    }
  }

  static class convert_meters_to_pixel_height extends SqlFunction {
    convert_meters_to_pixel_height() {
      super("convert_meters_to_pixel_height",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.ANY,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 6;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.DOUBLE);
    }
  }

  static class is_point_in_view extends SqlFunction {
    is_point_in_view() {
      super("is_point_in_view",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 5;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
    }
  }

  static class is_point_size_in_view extends SqlFunction {
    is_point_size_in_view() {
      super("is_point_size_in_view",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.family(SqlTypeFamily.ANY,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC,
                      SqlTypeFamily.NUMERIC),
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 6;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
    }
  }

  public static class usTimestamp extends SqlFunction {
    public usTimestamp() {
      super("usTIMESTAMP",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.STRING,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.TIMESTAMP, 6);
    }
  }

  public static class nsTimestamp extends SqlFunction {
    public nsTimestamp() {
      super("nsTIMESTAMP",
              SqlKind.OTHER_FUNCTION,
              null,
              null,
              OperandTypes.STRING,
              SqlFunctionCategory.SYSTEM);
    }

    @Override
    public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
      assert opBinding.getOperandCount() == 1;
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createSqlType(SqlTypeName.TIMESTAMP, 9);
    }
  }
}

// End HeavyDBSqlOperatorTable.java