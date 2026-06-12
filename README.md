# Declarative Patterns

Practical declarative patterns for code, architecture, and AI-assisted development.

This repository collects small, reusable patterns that make software behavior explicit, readable, and easier to follow for both human developers and AI coding assistants.

The patterns are stored as practical instruction files that you can copy, download, include in your project, or attach to your coding agent. The intended workflow is simple: give the relevant pattern file to your AI assistant and ask it to update your code following these exact patterns and instructions.

Instead of prompting:

```text
Write good Kotlin.
```

you can prompt and atach MD file with instructions:

```text
Update THISFILE following this patterns and instuctions
```

The goal is to make architectural preferences concrete enough that both humans and AI tools can reuse them consistently.

## First pattern

The first pattern is about declarative failure processing in Kotlin.

It translates a failure-processing approach into simple Kotlin rules:

* make failure paths explicit
* preserve the original cause
* avoid silent `null`
* avoid vague logs
* keep coroutine cancellation safe
* convert failures only at clear boundaries

## Repository structure

```text
failure-processing/
  kotlin/
    vanilla-failure-processing.md
    examples/
      authorize-user-before.kt
      authorize-user-after.kt
  dart/

architecture/

state/

ai-coding/
```

## About the author

This project is created and maintained by **Konstantin Voronov** as part of his broader work on declarative programming patterns, explicit architecture, and AI-assisted development workflows.

The patterns collected here come from practical production experience and are focused on making code behavior easier to understand, review, debug, and evolve.

Contact: [me@konstantinvoronov.com](mailto:me@konstantinvoronov.com)
