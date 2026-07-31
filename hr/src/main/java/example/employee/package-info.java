/**
 * The types in this package ({@code Employee} and the rest) are generated at compile time by
 * {@code SoutherProcessor} from {@code src/main/souther/employee.sou}. This {@code package-info} is the
 * minimal source that makes that happen: javac runs no annotation processing unless there is at least
 * one Java source to compile.
 *
 * <p>The module holds eight Souther modules, each generating into its own package
 * ({@code employee.sou} to {@code example.employee}, {@code dependents.sou} to
 * {@code example.dependents}, and likewise {@code example.socialinsurance},
 * {@code example.employmentinsurance}, {@code example.attendance}, {@code example.payroll},
 * {@code example.yearendadjustment} and {@code example.filing}). One file is still enough: what the
 * processor reads is the source directory handed to it by {@code -Asouther.source}, not a list of
 * packages.
 */
package example.employee;
