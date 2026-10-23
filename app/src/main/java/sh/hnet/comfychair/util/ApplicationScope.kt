package sh.hnet.comfychair.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Coroutine-Scope, der so lange lebt wie der App-Prozess.
 *
 * Für Arbeit, die nicht abbrechen soll, nur weil eine Activity endet
 * (z. B. den Medien-Cache speichern, wenn die App in den Hintergrund geht).
 * SupervisorJob: Ein Fehler in einer Aufgabe beendet nicht den ganzen Scope.
 */
object ApplicationScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)
