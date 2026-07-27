/**
 * The types ({@code Issue} / {@code Label} / {@code LabelSet} / {@code LabelCounts} / …) and behaviors
 * ({@code openIssue} / {@code findIssue} / {@code attachLabel} / {@code detachLabel} /
 * {@code sharedLabels} / {@code assigneeOf} / {@code countByLabel} / {@code topLabels}) in this package
 * are generated at compile time by {@code SoutherProcessor} from {@code src/main/souther/issues.sou}.
 * This {@code package-info} is the one minimal Java source that makes javac run annotation processing
 * (it will not without at least one source) — which is also what puts the generated classes in
 * {@code target/classes} before kotlinc compiles the boundary against them.
 *
 * <p>It carries {@code @NullMarked} because it is here: the processor emits that annotation for a
 * module's package itself, but not over a {@code package-info} the compilation already declares. What
 * it says is what the generated code is — absence is {@code Option}, a failure is a case of the output
 * union — and it is what makes the boundary's Kotlin read these types as {@code Issue} rather than
 * {@code Issue!}.
 */
@NullMarked
package example.issuetracker;

import org.jspecify.annotations.NullMarked;
