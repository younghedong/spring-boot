/*
 * Copyright 2012-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.build.architecture;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClass.Predicates;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With;
import com.tngtech.archunit.core.domain.properties.HasParameterTypes;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;

import org.springframework.util.ResourceUtils;

/**
 * {@link Task} that checks for architecture problems.
 *
 * @author Andy Wilkinson
 * @author Yanming Zhou
 * @author Scott Frederick
 * @author Ivan Malutin
 */
public abstract class ArchitectureCheck extends DefaultTask {

	private FileCollection classes;

	public ArchitectureCheck() {
		getOutputDirectory().convention(getProject().getLayout().getBuildDirectory().dir(getName()));
		getProhibitObjectsRequireNonNull().convention(true);
		getRules().addAll(allPackagesShouldBeFreeOfTangles(),
				allBeanPostProcessorBeanMethodsShouldBeStaticAndHaveParametersThatWillNotCausePrematureInitialization(),
				allBeanFactoryPostProcessorBeanMethodsShouldBeStaticAndHaveNoParameters(),
				noClassesShouldCallStepVerifierStepVerifyComplete(),
				noClassesShouldConfigureDefaultStepVerifierTimeout(), noClassesShouldCallCollectorsToList(),
				noClassesShouldCallURLEncoderWithStringEncoding(), noClassesShouldCallURLDecoderWithStringEncoding(),
				noClassesShouldLoadResourcesUsingResourceUtils(), noClassesShouldCallStringToUpperCaseWithoutLocale(),
				noClassesShouldCallStringToLowerCaseWithoutLocale(),
				conditionalOnMissingBeanShouldNotSpecifyOnlyATypeThatIsTheSameAsMethodReturnType(),
				enumSourceShouldNotSpecifyOnlyATypeThatIsTheSameAsMethodParameterType());
		getRules().addAll(getProhibitObjectsRequireNonNull()
			.map((prohibit) -> prohibit ? noClassesShouldCallObjectsRequireNonNull() : Collections.emptyList()));
		getRuleDescriptions().set(getRules().map((rules) -> rules.stream().map(ArchRule::getDescription).toList()));
	}

	@TaskAction
	void checkArchitecture() throws IOException {
		JavaClasses javaClasses = new ClassFileImporter()
			.importPaths(this.classes.getFiles().stream().map(File::toPath).toList());
		List<EvaluationResult> violations = getRules().get()
			.stream()
			.map((rule) -> rule.evaluate(javaClasses))
			.filter(EvaluationResult::hasViolation)
			.toList();
		File outputFile = getOutputDirectory().file("failure-report.txt").get().getAsFile();
		outputFile.getParentFile().mkdirs();
		if (!violations.isEmpty()) {
			StringBuilder report = new StringBuilder();
			for (EvaluationResult violation : violations) {
				report.append(violation.getFailureReport());
				report.append(String.format("%n"));
			}
			Files.writeString(outputFile.toPath(), report.toString(), StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING);
			throw new GradleException("Architecture check failed. See '" + outputFile + "' for details.");
		}
		else {
			outputFile.createNewFile();
		}
	}

	private ArchRule allPackagesShouldBeFreeOfTangles() {
		return SlicesRuleDefinition.slices().matching("(**)").should().beFreeOfCycles();
	}

	private ArchRule allBeanPostProcessorBeanMethodsShouldBeStaticAndHaveParametersThatWillNotCausePrematureInitialization() {
		return ArchRuleDefinition.methods()
			.that()
			.areAnnotatedWith("org.springframework.context.annotation.Bean")
			.and()
			.haveRawReturnType(Predicates.assignableTo("org.springframework.beans.factory.config.BeanPostProcessor"))
			.should(onlyHaveParametersThatWillNotCauseEagerInitialization())
			.andShould()
			.beStatic()
			.allowEmptyShould(true);
	}

	private ArchCondition<JavaMethod> onlyHaveParametersThatWillNotCauseEagerInitialization() {
		DescribedPredicate<CanBeAnnotated> notAnnotatedWithLazy = DescribedPredicate
			.not(CanBeAnnotated.Predicates.annotatedWith("org.springframework.context.annotation.Lazy"));
		DescribedPredicate<JavaClass> notOfASafeType = DescribedPredicate
			.not(Predicates.assignableTo("org.springframework.beans.factory.ObjectProvider")
				.or(Predicates.assignableTo("org.springframework.context.ApplicationContext"))
				.or(Predicates.assignableTo("org.springframework.core.env.Environment")));
		return new ArchCondition<>("not have parameters that will cause eager initialization") {

			@Override
			public void check(JavaMethod item, ConditionEvents events) {
				item.getParameters()
					.stream()
					.filter(notAnnotatedWithLazy)
					.filter((parameter) -> notOfASafeType.test(parameter.getRawType()))
					.forEach((parameter) -> events.add(SimpleConditionEvent.violated(parameter,
							parameter.getDescription() + " will cause eager initialization as it is "
									+ notAnnotatedWithLazy.getDescription() + " and is "
									+ notOfASafeType.getDescription())));
			}

		};
	}

	private ArchRule allBeanFactoryPostProcessorBeanMethodsShouldBeStaticAndHaveNoParameters() {
		return ArchRuleDefinition.methods()
			.that()
			.areAnnotatedWith("org.springframework.context.annotation.Bean")
			.and()
			.haveRawReturnType(
					Predicates.assignableTo("org.springframework.beans.factory.config.BeanFactoryPostProcessor"))
			.should(onlyInjectEnvironment())
			.andShould()
			.beStatic()
			.allowEmptyShould(true);
	}

	private ArchCondition<JavaMethod> onlyInjectEnvironment() {
		return new ArchCondition<>("only inject Environment") {

			@Override
			public void check(JavaMethod item, ConditionEvents events) {
				List<JavaParameter> parameters = item.getParameters();
				for (JavaParameter parameter : parameters) {
					if (!"org.springframework.core.env.Environment".equals(parameter.getType().getName())) {
						events.add(SimpleConditionEvent.violated(item,
								item.getDescription() + " should only inject Environment"));
					}
				}
			}

		};
	}

	private ArchRule noClassesShouldCallStringToLowerCaseWithoutLocale() {
		return ArchRuleDefinition.noClasses()
			.should()
			.callMethod(String.class, "toLowerCase")
			.because("String.toLowerCase(Locale.ROOT) should be used instead");
	}

	private ArchRule noClassesShouldCallStringToUpperCaseWithoutLocale() {
		return ArchRuleDefinition.noClasses()
			.should()
			.callMethod(String.class, "toUpperCase")
			.because("String.toUpperCase(Locale.ROOT) should be used instead");
	}

	private ArchRule noClassesShouldCallStepVerifierStepVerifyComplete() {
		return ArchRuleDefinition.noClasses()
			.should()
			.callMethod("reactor.test.StepVerifier$Step", "verifyComplete")
			.because("it can block indefinitely and expectComplete().verify(Duration) should be used instead");
	}

	private ArchRule noClassesShouldConfigureDefaultStepVerifierTimeout() {
		return ArchRuleDefinition.noClasses()
			.should()
			.callMethod("reactor.test.StepVerifier", "setDefaultTimeout", "java.time.Duration")
			.because("expectComplete().verify(Duration) should be used instead");
	}

	private ArchRule noClassesShouldCallCollectorsToList() {
		return ArchRuleDefinition.noClasses()
			.should()
			.callMethod(Collectors.class, "toList")
			.because("java.util.stream.Stream.toList() should be used instead");
	}

	private ArchRule noClassesShouldCallURLEncoderWithStringEncoding() {
		return ArchRuleDefinition.noClasses()
			.should()
			.callMethod(URLEncoder.class, "encode", String.class, String.class)
			.because("java.net.URLEncoder.encode(String s, Charset charset) should be used instead");
	}

	private ArchRule noClassesShouldCallURLDecoderWithStringEncoding() {
		return ArchRuleDefinition.noClasses()
			.should()
			.callMethod(URLDecoder.class, "decode", String.class, String.class)
			.because("java.net.URLDecoder.decode(String s, Charset charset) should be used instead");
	}

	private ArchRule noClassesShouldLoadResourcesUsingResourceUtils() {
		return ArchRuleDefinition.noClasses()
			.should()
			.callMethodWhere(JavaCall.Predicates.target(With.owner(Predicates.type(ResourceUtils.class)))
				.and(JavaCall.Predicates.target(HasName.Predicates.name("getURL")))
				.and(JavaCall.Predicates.target(HasParameterTypes.Predicates.rawParameterTypes(String.class)))
				.or(JavaCall.Predicates.target(With.owner(Predicates.type(ResourceUtils.class)))
					.and(JavaCall.Predicates.target(HasName.Predicates.name("getFile")))
					.and(JavaCall.Predicates.target(HasParameterTypes.Predicates.rawParameterTypes(String.class)))))
			.because("org.springframework.boot.io.ApplicationResourceLoader should be used instead");
	}

	private List<ArchRule> noClassesShouldCallObjectsRequireNonNull() {
		return List.of(
				ArchRuleDefinition.noClasses()
					.should()
					.callMethod(Objects.class, "requireNonNull", Object.class, String.class)
					.because("org.springframework.utils.Assert.notNull(Object, String) should be used instead"),
				ArchRuleDefinition.noClasses()
					.should()
					.callMethod(Objects.class, "requireNonNull", Object.class, Supplier.class)
					.because("org.springframework.utils.Assert.notNull(Object, Supplier) should be used instead"));
	}

	private ArchRule conditionalOnMissingBeanShouldNotSpecifyOnlyATypeThatIsTheSameAsMethodReturnType() {
		return ArchRuleDefinition.methods()
			.that()
			.areAnnotatedWith("org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean")
			.should(notSpecifyOnlyATypeThatIsTheSameAsTheMethodReturnType())
			.allowEmptyShould(true);
	}

	private ArchCondition<? super JavaMethod> notSpecifyOnlyATypeThatIsTheSameAsTheMethodReturnType() {
		return new ArchCondition<>("not specify only a type that is the same as the method's return type") {

			@Override
			public void check(JavaMethod item, ConditionEvents events) {
				JavaAnnotation<JavaMethod> conditional = item
					.getAnnotationOfType("org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean");
				Map<String, Object> properties = conditional.getProperties();
				if (!properties.containsKey("type") && !properties.containsKey("name")) {
					conditional.get("value").ifPresent((value) -> {
						JavaType[] types = (JavaType[]) value;
						if (types.length == 1 && item.getReturnType().equals(types[0])) {
							events.add(SimpleConditionEvent.violated(item, conditional.getDescription()
									+ " should not specify only a value that is the same as the method's return type"));
						}
					});
				}
			}

		};
	}

	private ArchRule enumSourceShouldNotSpecifyOnlyATypeThatIsTheSameAsMethodParameterType() {
		return ArchRuleDefinition.methods()
			.that()
			.areAnnotatedWith("org.junit.jupiter.params.provider.EnumSource")
			.should(notSpecifyOnlyATypeThatIsTheSameAsTheMethodParameterType())
			.allowEmptyShould(true);
	}

	private ArchCondition<? super JavaMethod> notSpecifyOnlyATypeThatIsTheSameAsTheMethodParameterType() {
		return new ArchCondition<>("not specify only a type that is the same as the method's parameter type") {

			@Override
			public void check(JavaMethod item, ConditionEvents events) {
				JavaAnnotation<JavaMethod> conditional = item
					.getAnnotationOfType("org.junit.jupiter.params.provider.EnumSource");
				Map<String, Object> properties = conditional.getProperties();
				if (properties.size() == 1 && item.getParameterTypes().size() == 1) {
					conditional.get("value").ifPresent((value) -> {
						if (value.equals(item.getParameterTypes().get(0))) {
							events.add(SimpleConditionEvent.violated(item, conditional.getDescription()
									+ " should not specify only a value that is the same as the method's parameter type"));
						}
					});
				}
			}

		};
	}

	public void setClasses(FileCollection classes) {
		this.classes = classes;
	}

	@Internal
	public FileCollection getClasses() {
		return this.classes;
	}

	@InputFiles
	@SkipWhenEmpty
	@IgnoreEmptyDirectories
	@PathSensitive(PathSensitivity.RELATIVE)
	final FileTree getInputClasses() {
		return this.classes.getAsFileTree();
	}

	@Optional
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getResourcesDirectory();

	@OutputDirectory
	public abstract DirectoryProperty getOutputDirectory();

	@Internal
	public abstract ListProperty<ArchRule> getRules();

	@Internal
	public abstract Property<Boolean> getProhibitObjectsRequireNonNull();

	@Input
	// The rules themselves can't be an input as they aren't serializable so we use
	// their descriptions instead
	abstract ListProperty<String> getRuleDescriptions();

}
