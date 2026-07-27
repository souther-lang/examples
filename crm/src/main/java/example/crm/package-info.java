/**
 * このパッケージの型（{@code ContactPoint} など）は {@code src/main/souther/crm.sou} から
 * コンパイル時に {@code SoutherProcessor} が生成する。この {@code package-info} は、生成を
 * 走らせるための最小のソース（javac はソースが1つ以上ないとアノテーション処理を起動しない）。
 *
 * <p>このモジュールには Souther モジュールが5つあり、それぞれ別のパッケージへ生成される
 * （{@code crm.sou} → {@code example.crm}、{@code pipeline.sou} → {@code example.pipeline}、
 * {@code activity.sou} → {@code example.activity}、{@code quoting.sou} → {@code example.quoting}、
 * {@code forecasting.sou} → {@code example.forecasting}）。それでもこのファイルは1つで足りる。
 * プロセッサが読むのは {@code -Asouther.source} で渡されたソースディレクトリであって、
 * パッケージの一覧ではないので、javac を起動させる Java ソースが1つあれば5モジュールすべてが
 * コンパイルされる。
 */
package example.crm;
