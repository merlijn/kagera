# Kagera. A Discrete, colored Petri Net DSL & Executor.

Example of a simple colored petrinet with functions acting on the data in the tokens.

```
val sum = {

    // places
    val a = Place[Int](id = 1, label = "a")
    val b = Place[Int](id = 2, label = "b")
    val c = Place[Int](id = 3, label = "result")

    // transitions
    val init = Transition(id = 1, label = "init") {
      () ⇒ (5, 5)
    }

    val sum = Transition(id = 2, label = "sum") {
      (a: Int, b: Int) ⇒ a + b
    }

    // process topology
    process(
      init    ~> %(a, b),
      %(a, b) ~> sum,
      sum     ~> c)
  }

```

## Transition Model

![Transition Model](docs/Kagera%20-%20Transition%20model.jpg)

Input:
* `M` is the in-adjacent marking of the transition.
* `S` is the type of state the transition closes over, `Unit` indicates no state.
* `I` Is the type of input the transition requires (provided from outside the proces), `Unit` indicates case no input is required.

Output:
* `M'` is the out-adjacent marking of the transition.
* `E` Is the type of event or output the transition emits.  `Unit` indicates no event / output.

The transition's behaviour is defined by 2 functions

1. `(M, I, S) => (M', E)`
   A function producing the out-adjacent marking and event from in-adjancent marking, state and input.
2. `S => E => S`
   An event sourcing function which updates the state using the emitted event / output object.

## Roadmap

Short term:
* Http API (using akka http)
* Clustering & Sharding
* Analysis (using apache Spark or apache Flink), some examples:
  * Path querying
  * Path prediction
  * Transition timing

Long term:
* Hyarchical petri nets
* Timed petri nets
* Other execution models, such as:
  * Splitting processes over multiple actors, possibly on different nodes in a cluster.
  * A petri net process as a Stream (using https://github.com/functional-streams-for-scala/fs2 or akka streams for example).
* Process migration
  * Providing tools to migrate process state when the petri net topology changes.
* Visual process editor


