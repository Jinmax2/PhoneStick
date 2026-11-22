# R8 规则（release 开启 minifyEnabled + shrinkResources）。
#
# 项目自身无反射/JNI/序列化，libsu、material、appcompat 等库自带 consumer
# rules，通常无需额外 keep。若真机回归发现 release 包行为异常，先怀疑
# R8 裁剪，再考虑在这里补 keep 规则。
#
# 参考: https://developer.android.com/build/shrink-code
