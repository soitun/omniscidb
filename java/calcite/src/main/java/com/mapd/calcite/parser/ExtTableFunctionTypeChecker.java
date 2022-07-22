package com.mapd.calcite.parser;

import com.mapd.calcite.parser.HeavyDBSqlOperatorTable.ExtTableFunction;
import com.mapd.parser.server.ExtensionFunction;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlNameMatchers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ExtTableFunctionTypeChecker implements SqlOperandTypeChecker {
  final HeavyDBSqlOperatorTable opTable;

  ExtTableFunctionTypeChecker(HeavyDBSqlOperatorTable opTable) {
    this.opTable = opTable;
  }

  public boolean isOptional(int argIndex) {
    // We need to mark all arguments as optional, otherwise Calcite may invalidate some
    // valid calls during the validation process. This is because if it previously bound
    // the call to a UDTF operator that receives more arguments, it fills up the missing
    // arguments with DEFAULTs. DEFAULT arguments however will not get to the typechecking
    // stage if they are not optional for that oeprator.
    return true;
  }

  public boolean doesOperandTypeMatch(ExtTableFunction tf,
          SqlCallBinding callBinding,
          SqlNode node,
          int iFormalOperand) {
    SqlCall permutedCall = callBinding.permutedCall();
    SqlNode permutedOperand = permutedCall.operand(iFormalOperand);
    RelDataType type;

    // For candidate calls to incompatible operators, type inference of operands may fail.
    // In that case, we just catch the exception and invalidade the candidate.
    try {
      type = callBinding.getValidator().deriveType(
              callBinding.getScope(), permutedOperand);
    } catch (Exception e) {
      return false;
    }
    SqlTypeName typeName = type.getSqlTypeName();

    if (typeName == SqlTypeName.CURSOR) {
      SqlCall cursorCall = (SqlCall) permutedOperand;
      RelDataType cursorType = callBinding.getValidator().deriveType(
              callBinding.getScope(), cursorCall.operand(0));
      return doesCursorOperandTypeMatch(tf, iFormalOperand, cursorType);
    } else {
      return tf.getArgTypes().get(iFormalOperand).getTypeNames().contains(typeName);
    }
  }

  public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
    Set<ExtTableFunction> candidateOverloads = new HashSet<ExtTableFunction>(
            getOperatorOverloads(callBinding.getOperator()));

    // Remove all candidates whose number of formal args doesn't match the
    // call's number of real args.
    candidateOverloads.removeIf(
            tf -> tf.getArgTypes().size() != callBinding.getOperandCount());

    SqlNode[] operandArray = new SqlNode[callBinding.getCall().getOperandList().size()];
    for (Ord<SqlNode> arg : Ord.zip(callBinding.getCall().getOperandList())) {
      operandArray[arg.i] = arg.e;
    }

    // Construct a candidate call binding for each overload. We need to do this because
    // type inference of operands may differ depending on which operator is used. Thus,
    // typechecking needs to be done on a candidate call-by-call basis.
    HashMap<ExtTableFunction, SqlCallBinding> candidateBindings =
            new HashMap<>(candidateOverloads.size());
    for (ExtTableFunction tf : candidateOverloads) {
      SqlBasicCall newCall = new SqlBasicCall(
              tf, operandArray, callBinding.getCall().getParserPosition());
      SqlCallBinding candidateBinding = new SqlCallBinding(
              callBinding.getValidator(), callBinding.getScope(), newCall);
      candidateBindings.put(tf, candidateBinding);
    }

    for (int i = 0; i < operandArray.length; i++) {
      int idx = i;
      candidateOverloads.removeIf(tf
              -> !doesOperandTypeMatch(
                      tf, candidateBindings.get(tf), operandArray[idx], idx));
    }

    // If there are no candidates left, the call is invalid.
    if (candidateOverloads.size() == 0) {
      if (throwOnFailure) {
        throw(callBinding.newValidationSignatureError());
      }
      return false;
    }

    // If there are candidates left, and the current bound operator
    // is not one of them, rewrite the call to use the a better binding.
    if (!candidateOverloads.isEmpty()
            && !candidateOverloads.contains(callBinding.getOperator())) {
      ExtTableFunction optimal = candidateOverloads.iterator().next();
      ((SqlBasicCall) callBinding.getCall()).setOperator(optimal);
    }

    return true;
  }

  public boolean doesCursorOperandTypeMatch(
          ExtTableFunction tf, int iFormalOperand, RelDataType actualOperand) {
    String formalOperandName = tf.getExtendedParamNames().get(iFormalOperand);
    List<ExtensionFunction.ExtArgumentType> formalFieldTypes =
            tf.getCursorFieldTypes().get(formalOperandName);
    List<RelDataTypeField> actualFieldList = actualOperand.getFieldList();

    // runtime functions may not have CURSOR field type information, so we default
    // to old behavior of assuming they typecheck
    if (formalFieldTypes.size() == 0) {
      System.out.println(
              "Warning: UDTF has no CURSOR field subtype data. Proceeding assuming CURSOR typechecks.");
      return true;
    }

    int iFormal = 0;
    int iActual = 0;
    while (iActual < actualFieldList.size() && iFormal < formalFieldTypes.size()) {
      ExtensionFunction.ExtArgumentType extType = formalFieldTypes.get(iFormal);
      SqlTypeName formalType = ExtensionFunction.toSqlTypeName(extType);
      SqlTypeName actualType = actualFieldList.get(iActual).getValue().getSqlTypeName();

      if (formalType == SqlTypeName.COLUMN_LIST) {
        ExtensionFunction.ExtArgumentType colListSubtype =
                ExtensionFunction.getValueType(extType);
        SqlTypeName colListType = ExtensionFunction.toSqlTypeName(colListSubtype);

        if (actualType != colListType) {
          return false;
        }

        int colListSize = 0;
        int numFormalArgumentsLeft = (formalFieldTypes.size() - 1) - iFormal;
        while (iActual + colListSize
                < (actualFieldList.size() - numFormalArgumentsLeft)) {
          actualType =
                  actualFieldList.get(iActual + colListSize).getValue().getSqlTypeName();
          if (actualType != colListType) {
            break;
          }
          colListSize++;
        }
        iActual += colListSize - 1;
      } else if (formalType != actualType) {
        return false;
      }
      iFormal++;
      iActual++;
    }

    if (iActual < actualFieldList.size()) {
      return false;
    }

    return true;
  }

  public List<ExtTableFunction> getOperatorOverloads(SqlOperator op) {
    List<SqlOperator> overloads = new ArrayList<>();
    opTable.lookupOperatorOverloads(op.getNameAsId(),
            SqlFunctionCategory.USER_DEFINED_TABLE_FUNCTION,
            SqlSyntax.FUNCTION,
            overloads,
            SqlNameMatchers.liberal());

    return overloads.stream()
            .filter(p -> p instanceof ExtTableFunction)
            .map(p -> (ExtTableFunction) p)
            .collect(Collectors.toList());
  }

  public SqlOperandCountRange getOperandCountRange() {
    return SqlOperandCountRanges.any();
  }

  public String getAllowedSignatures(SqlOperator op, String opName) {
    List<ExtTableFunction> overloads = getOperatorOverloads(op);
    return String.join(System.lineSeparator() + "\t",
            overloads.stream()
                    .map(tf -> tf.getExtendedSignature())
                    .collect(Collectors.toList()));
  }

  public Consistency getConsistency() {
    return Consistency.NONE;
  }
}