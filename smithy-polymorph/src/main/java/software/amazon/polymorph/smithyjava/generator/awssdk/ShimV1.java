package software.amazon.polymorph.smithyjava.generator.awssdk;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.stream.Collectors;

import javax.lang.model.element.Modifier;

import software.amazon.polymorph.smithyjava.generator.Generator;
import software.amazon.polymorph.smithyjava.nameresolver.AwsSdkDafnyV1;
import software.amazon.polymorph.smithyjava.nameresolver.AwsSdkNativeV1;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.StringUtils;

import static software.amazon.polymorph.smithyjava.nameresolver.Constants.DAFNY_RESULT_CLASS_NAME;
import static software.amazon.polymorph.smithyjava.nameresolver.Constants.DAFNY_TUPLE0_CLASS_NAME;
import static software.amazon.polymorph.smithyjava.nameresolver.Constants.SMITHY_API_UNIT;


/**
 * Generates an AWS SDK Shim for the AWS SKD for Java V1
 * exposing an AWS Service's operations to Dafny Generated Java.
 */
public class ShimV1 extends Generator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShimV1.class);
    // These overrides Generator's nameResolvers to be AwsSdk specific name resolvers
    AwsSdkDafnyV1 dafnyNameResolver;
    AwsSdkNativeV1 nativeNameResolver;
    public ShimV1(AwsSdkV1 awsSdk) {
        super(awsSdk);
        dafnyNameResolver = awsSdk.dafnyNameResolver;
        nativeNameResolver = awsSdk.nativeNameResolver;
    }

    @Override
    public JavaFile javaFile(final ShapeId serviceShapeId) {
        return JavaFile.builder(dafnyNameResolver.packageName(), shim(serviceShapeId))
                .build();
    }

    TypeSpec shim(final ShapeId serviceShapeId) {
        final ServiceShape serviceShape = model.expectShape(serviceShapeId, ServiceShape.class);
        return TypeSpec
                .classBuilder(
                        ClassName.get(dafnyNameResolver.packageName(), "Shim"))
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(dafnyNameResolver.typeForShape(serviceShapeId))
                .addField(
                        nativeNameResolver.typeForService(serviceShape),
                        "_impl", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(constructor())
                .addMethods(
                        serviceShape.getAllOperations()
                                .stream()
                                .map(this::operation)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .collect(Collectors.toList()))
                .build();
    }

    MethodSpec constructor() {
        return MethodSpec
                .constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(
                        nativeNameResolver.typeForService(serviceShape),
                        "impl")
                .addStatement("_impl = impl")
                .build();
    }

    Optional<MethodSpec> operation(final ShapeId operationShapeId) {
        final OperationShape operationShape = model.expectShape(operationShapeId, OperationShape.class);
        ShapeId inputShapeId = operationShape.getInputShape();
        ShapeId outputShapeId = operationShape.getOutputShape();
        TypeName dafnyOutput = dafnyNameResolver.typeForShape(outputShapeId);
        String operationName = operationShape.toShapeId().getName();
        MethodSpec.Builder builder = MethodSpec
                .methodBuilder(StringUtils.capitalize(operationName))
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(
                        asDafnyResult(
                                dafnyOutput,
                                dafnyNameResolver.getDafnyAbstractServiceError()
                        ))
                .addParameter(dafnyNameResolver.typeForShape(inputShapeId), "input")
                .addStatement("$T converted = ToNative.$L(input)",
                        nativeNameResolver.typeForShape(inputShapeId),
                        StringUtils.capitalize(inputShapeId.getName()))
                .beginControlFlow("try");
        if (outputShapeId.equals(SMITHY_API_UNIT)) {
            builder.addStatement("_impl.$L(converted)",
                            StringUtils.uncapitalize(operationName))
                    .addStatement("return $T.create_Success($T.create())",
                            DAFNY_RESULT_CLASS_NAME, DAFNY_TUPLE0_CLASS_NAME);
        } else {
            builder.addStatement("$T result = _impl.$L(converted)",
                            nativeNameResolver.typeForOperationOutput(outputShapeId),
                            StringUtils.uncapitalize(operationName))
                    .addStatement("$T dafnyResponse = ToDafny.$L(result)",
                            dafnyOutput,
                            StringUtils.capitalize(outputShapeId.getName()))
                    .addStatement("return $T.create_Success(dafnyResponse)",
                            DAFNY_RESULT_CLASS_NAME);
        }

        operationShape.getErrors().forEach(shapeId ->
                builder
                        .nextControlFlow("catch ($T ex)", nativeNameResolver.typeForShape(shapeId))
                        .addStatement("return $T.create_Failure(ToDafny.Error(ex))",
                                DAFNY_RESULT_CLASS_NAME)
        );
        return Optional.of(builder
                .nextControlFlow("catch ($T ex)", nativeNameResolver.baseErrorForService())
                .addStatement("return $T.create_Failure(ToDafny.Error(ex))",
                        DAFNY_RESULT_CLASS_NAME)
                .endControlFlow()
                .build());
    }

    private TypeName asDafnyResult(TypeName success, TypeName failure) {
        return ParameterizedTypeName.get(
                DAFNY_RESULT_CLASS_NAME,
                success,
                failure
        );
    }
}