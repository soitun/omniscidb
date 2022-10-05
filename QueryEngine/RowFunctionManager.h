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

#pragma once

#include <boost/algorithm/string/predicate.hpp>
#include "QueryEngine/Execute.h"

// copied from TableFunctionsFactory.cpp
namespace {

std::string drop_suffix_impl(const std::string& str) {
  const auto idx = str.find("__");
  if (idx == std::string::npos) {
    return str;
  }
  CHECK_GT(idx, std::string::size_type(0));
  return str.substr(0, idx);
}

}  // namespace

struct RowFunctionManager {
  RowFunctionManager(const Executor* executor, const RelAlgExecutionUnit& ra_exe_unit)
      : executor_(executor), ra_exe_unit_(ra_exe_unit) {}

  inline std::string getString(int32_t dict_id, int32_t string_id) {
    const auto proxy = executor_->getStringDictionaryProxy(
        dict_id, executor_->getRowSetMemoryOwner(), true);
    return proxy->getString(string_id);
  }

  inline int32_t getDictId(const std::string& func_name, size_t arg_idx) {
    std::string func_name_wo_suffix =
        boost::algorithm::to_lower_copy(drop_suffix_impl(func_name));

    for (const auto& expr : ra_exe_unit_.target_exprs) {
      if (const Analyzer::FunctionOper* function_oper =
              dynamic_cast<Analyzer::FunctionOper*>(expr)) {
        std::string func_oper_name = drop_suffix_impl(function_oper->getName());
        boost::algorithm::to_lower(func_oper_name);
        if (func_name_wo_suffix == func_oper_name) {
          CHECK_LT(arg_idx, function_oper->getArity());
          const SQLTypeInfo typ = function_oper->getArg(arg_idx)->get_type_info();
          CHECK(typ.is_text_encoding_dict() || typ.is_text_encoding_dict_array());
          return typ.get_comp_param();
        }
      }
    }
    UNREACHABLE();
    return 0;
  }

  inline int32_t getOrAddTransient(int32_t dict_id, std::string str) {
    const auto proxy = executor_->getStringDictionaryProxy(
        dict_id, executor_->getRowSetMemoryOwner(), true);
    return proxy->getOrAddTransient(str);
  }

  inline int8_t* getStringDictionaryProxy(int32_t dict_id) {
    auto* proxy = executor_->getStringDictionaryProxy(
        dict_id, executor_->getRowSetMemoryOwner(), true);
    return reinterpret_cast<int8_t*>(proxy);
  }

  // Executor
  const Executor* executor_;
  const RelAlgExecutionUnit& ra_exe_unit_;
};