/*
 * Copyright 2011-2017 Tim Berglund and Steven C. Saliman
 * Kotlin conversion done by Jason Blackwell
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package liquibase.parser.ext

import liquibase.changelog.ChangeLogParameters
import liquibase.changelog.DatabaseChangeLog
import liquibase.exception.ChangeLogParseException
import liquibase.parser.ChangeLogParser
import liquibase.resource.ResourceAccessor
import org.liquibase.kotlin.KotlinDatabaseChangeLog
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import javax.script.Compilable
import javax.script.ScriptEngineManager
import javax.script.ScriptException

/**
 * This is the main parser class for the Liquibase Kotlin DSL.  It is the
 * integration point to Liquibase itself.  It must be in the
 * liquibase.parser.ext package to be found by Liquibase at runtime.
 *
 * @author Tim Berglund
 * @author Steven C. Saliman
 * @author Jason Blackwell
 */
open class KotlinLiquibaseChangeLogParser : ChangeLogParser {
	override fun parse(physicalChangeLogLocation: String, changeLogParameters: ChangeLogParameters?,
			  resourceAccessor: ResourceAccessor): DatabaseChangeLog {

		val realLocation = physicalChangeLogLocation.replace("\\\\", "/")
		val inputStreams = resourceAccessor.getResourcesAsStream(realLocation)
		if (inputStreams == null || inputStreams.size < 1) {
			throw ChangeLogParseException("$realLocation does not exist")
		}

		inputStreams.first().use { inputStream ->
			val engine = ScriptEngineManager().getEngineByExtension("kts")!! as Compilable

			val err = System.err
			val out = System.out
			val compiled = try {
				PrintStream(ByteArrayOutputStream()).use {
					//Set the err and out values to avoid junk messages being written to the console.
					System.setErr(it)
					System.setOut(it)
					InputStreamReader(inputStream).use {
						engine.compile(it)
					}
				}
			} catch (e: ScriptException) {
				throw ScriptException("Compilation error", "$physicalChangeLogLocation.kts", e.lineNumber, e.columnNumber)
						.apply { initCause(e) }
			} finally {
				System.setErr(err)
				System.setOut(out)
			}

			@Suppress("UNCHECKED_CAST")
			val clPair = compiled.eval() as? Pair<String?, ((KotlinDatabaseChangeLog).() -> Unit)?>
					?: throw IllegalArgumentException("eval returned something unexpected")

			val changeLog = DatabaseChangeLog(clPair.first ?: physicalChangeLogLocation)
			// The changeLog will have been populated by the script
			changeLog.changeLogParameters = changeLogParameters

			val ktChangeLog = KotlinDatabaseChangeLog(changeLog)
			ktChangeLog.resourceAccessor = resourceAccessor

			clPair.second?.let {
				ktChangeLog.it()
			}

			return changeLog
		}
	}

	override fun supports(changeLogFile: String, resourceAccessor: ResourceAccessor): Boolean {
		return changeLogFile.endsWith(".kts")
	}

	override fun getPriority(): Int = ChangeLogParser.PRIORITY_DEFAULT
}