package dry4java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.type.PrimitiveType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class JavaNormalizer {
    NormalizedNode normalize(Node node) {
        List<NormalizedNode> children = new ArrayList<>();
        for (String marker : markers(node)) {
            children.add(new NormalizedNode(marker, List.of()));
        }
        for (Node child : node.getChildNodes()) {
            if (keepsStructuralChild(child)) {
                children.add(normalize(child));
            }
        }
        return new NormalizedNode(tag(node), children);
    }

    private String tag(Node node) {
        if (node instanceof EnumConstantDeclaration) {
            return "EnumConstant";
        }
        return node.getClass().getSimpleName();
    }

    private boolean keepsStructuralChild(Node child) {
        String simpleName = child.getClass().getSimpleName();
        return !simpleName.equals("SimpleName")
                && !simpleName.endsWith("LiteralExpr")
                && !simpleName.equals("Name")
                && !simpleName.equals("NameExpr")
                && !simpleName.equals("Comment");
    }

    private List<String> markers(Node node) {
        List<String> markers = new ArrayList<>();
        if (node instanceof BodyDeclaration<?> bodyDeclaration) {
            bodyDeclaration.getAnnotations().forEach(annotation -> markers.add("annotation"));
        }
        if (node instanceof NodeWithModifiers<?> withModifiers) {
            withModifiers.getModifiers().stream()
                    .map(Modifier::getKeyword)
                    .map(Enum::name)
                    .sorted()
                    .forEach(modifier -> markers.add("modifier:" + modifier));
        }
        if (node instanceof BinaryExpr binaryExpr) {
            markers.add("operator:" + binaryExpr.getOperator().name());
        } else if (node instanceof UnaryExpr unaryExpr) {
            markers.add("operator:" + unaryExpr.getOperator().name());
        } else if (node instanceof AssignExpr assignExpr) {
            markers.add("operator:" + assignExpr.getOperator().name());
        } else if (node instanceof VariableDeclarationExpr variableDeclarationExpr) {
            variableDeclarationExpr.getModifiers().stream()
                    .map(Modifier::getKeyword)
                    .map(Enum::name)
                    .sorted()
                    .forEach(modifier -> markers.add("modifier:" + modifier));
        } else if (node instanceof PrimitiveType primitiveType) {
            markers.add("primitive:" + primitiveType.getType().name());
        } else if (node instanceof SwitchEntry switchEntry) {
            markers.add("switch:" + switchEntry.getType().name());
        } else if (node instanceof LambdaExpr lambdaExpr && lambdaExpr.isEnclosingParameters()) {
            markers.add("lambda:parenthesized");
        }
        markers.sort(Comparator.naturalOrder());
        return markers;
    }
}
