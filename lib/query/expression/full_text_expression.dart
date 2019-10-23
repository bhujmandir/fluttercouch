import 'expression.dart';

class FullTextExpression {
  final String indexName;

  factory FullTextExpression.index(String name) {
    return FullTextExpression._internal(name);
  }

  FullTextExpression._internal(this.indexName);

  Expression match(String query) {
    return InternalFullTextExpression({"fullText": "$indexName|$query"});
  }
}

class InternalFullTextExpression extends Object with Expression {
  InternalFullTextExpression(Map<String, dynamic> _passedInternalExpression) {
    this.internalExpressionStack.add(_passedInternalExpression);
  }

  InternalFullTextExpression._clone(InternalFullTextExpression expression) {
    this.internalExpressionStack.addAll(expression.expressionStack);
  }

  @override
  InternalFullTextExpression clone() {
    return InternalFullTextExpression._clone(this);
  }
}