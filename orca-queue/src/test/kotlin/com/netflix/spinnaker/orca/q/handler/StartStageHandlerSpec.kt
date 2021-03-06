/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.q.handler

import com.natpryce.hamkrest.allElements
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.hasElement
import com.natpryce.hamkrest.isEmpty
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spinnaker.orca.ExecutionStatus.*
import com.netflix.spinnaker.orca.events.StageStarted
import com.netflix.spinnaker.orca.pipeline.RestrictExecutionDuringTimeWindow
import com.netflix.spinnaker.orca.pipeline.model.Pipeline
import com.netflix.spinnaker.orca.pipeline.model.Stage
import com.netflix.spinnaker.orca.pipeline.model.SyntheticStageOwner.STAGE_BEFORE
import com.netflix.spinnaker.orca.pipeline.model.Task
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionNotFoundException
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor
import com.netflix.spinnaker.orca.q.*
import com.netflix.spinnaker.orca.time.fixedClock
import com.netflix.spinnaker.spek.and
import com.netflix.spinnaker.spek.shouldEqual
import com.nhaarman.mockito_kotlin.*
import org.jetbrains.spek.api.dsl.context
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.subject.SubjectSpek
import org.springframework.context.ApplicationEventPublisher

object StartStageHandlerSpec : SubjectSpek<StartStageHandler>({

  val queue: Queue = mock()
  val repository: ExecutionRepository = mock()
  val publisher: ApplicationEventPublisher = mock()
  val clock = fixedClock()

  subject {
    StartStageHandler(
      queue,
      repository,
      listOf(
        singleTaskStage,
        multiTaskStage,
        stageWithSyntheticBefore,
        stageWithSyntheticAfter,
        stageWithParallelBranches,
        rollingPushStage,
        zeroTaskStage,
        stageWithSyntheticAfterAndNoTasks
      ),
      publisher,
      clock,
      ContextParameterProcessor()
    )
  }

  fun resetMocks() = reset(queue, repository, publisher)

  describe("starting a stage") {
    context("with a single initial task") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          type = singleTaskStage.type
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("updates the stage status") {
        verify(repository).storeStage(check {
          it.getStatus() shouldEqual RUNNING
          it.getStartTime() shouldEqual clock.millis()
        })
      }

      it("attaches tasks to the stage") {
        verify(repository).storeStage(check {
          it.getTasks().size shouldEqual 1
          it.getTasks().first().apply {
            id shouldEqual "1"
            name shouldEqual "dummy"
            implementingClass shouldEqual DummyTask::class.java.name
            isStageStart shouldEqual true
            isStageEnd shouldEqual true
          }
        })
      }

      it("starts the first task") {
        verify(queue).push(StartTask(message, "1"))
      }

      it("publishes an event") {
        verify(publisher).publishEvent(check<StageStarted> {
          it.executionType shouldEqual pipeline.javaClass
          it.executionId shouldEqual pipeline.id
          it.stageId shouldEqual message.stageId
        })
      }
    }

    context("with no tasks") {
      and("no after stages") {
        val pipeline = pipeline {
          application = "foo"
          stage {
            refId = "1"
            type = zeroTaskStage.type
          }
        }
        val message = StartStage(pipeline.stageByRef("1"))

        beforeGroup {
          whenever(repository.retrievePipeline(message.executionId)) doReturn pipeline
        }

        afterGroup(::resetMocks)

        action("the handler receives a message") {
          subject.handle(message)
        }

        it("updates the stage status") {
          verify(repository).storeStage(check {
            it.getStatus() shouldEqual RUNNING
            it.getStartTime() shouldEqual clock.millis()
          })
        }

        it("immediately completes the stage") {
          verify(queue).push(CompleteStage(message, SUCCEEDED))
          verifyNoMoreInteractions(queue)
        }

        it("publishes an event") {
          verify(publisher).publishEvent(check<StageStarted> {
            it.executionType shouldEqual pipeline.javaClass
            it.executionId shouldEqual pipeline.id
            it.stageId shouldEqual message.stageId
          })
        }
      }

      and("at least one after stage") {
        val pipeline = pipeline {
          application = "foo"
          stage {
            refId = "1"
            type = stageWithSyntheticAfterAndNoTasks.type
          }
        }
        val message = StartStage(pipeline.stageByRef("1"))

        beforeGroup {
          whenever(repository.retrievePipeline(message.executionId)) doReturn pipeline
        }

        afterGroup(::resetMocks)

        action("the handler receives a message") {
          subject.handle(message)
        }

        it("updates the stage status") {
          verify(repository).storeStage(check {
            it.getStatus() shouldEqual RUNNING
            it.getStartTime() shouldEqual clock.millis()
          })
        }

        it("immediately starts the first after stage") {
          verify(queue).push(StartStage(pipeline.stageByRef("1>1")))
          verifyNoMoreInteractions(queue)
        }

        it("publishes an event") {
          verify(publisher).publishEvent(check<StageStarted> {
            it.executionType shouldEqual pipeline.javaClass
            it.executionId shouldEqual pipeline.id
            it.stageId shouldEqual message.stageId
          })
        }
      }
    }

    context("with several linear tasks") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          type = multiTaskStage.type
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId)) doReturn pipeline
      }

      action("the handler receives a message") {
        subject.handle(message)
      }

      afterGroup(::resetMocks)

      it("attaches tasks to the stage") {
        verify(repository).storeStage(check {
          it.getTasks().size shouldEqual 3
          it.getTasks()[0].apply {
            id shouldEqual "1"
            name shouldEqual "dummy1"
            implementingClass shouldEqual DummyTask::class.java.name
            isStageStart shouldEqual true
            isStageEnd shouldEqual false
          }
          it.getTasks()[1].apply {
            id shouldEqual "2"
            name shouldEqual "dummy2"
            implementingClass shouldEqual DummyTask::class.java.name
            isStageStart shouldEqual false
            isStageEnd shouldEqual false
          }
          it.getTasks()[2].apply {
            id shouldEqual "3"
            name shouldEqual "dummy3"
            implementingClass shouldEqual DummyTask::class.java.name
            isStageStart shouldEqual false
            isStageEnd shouldEqual true
          }
        })
      }

      it("starts the first task") {
        verify(queue).push(StartTask(
          message.executionType,
          message.executionId,
          "foo",
          message.stageId,
          "1"
        ))
      }
    }

    context("with synthetic stages") {
      context("before the main stage") {
        val pipeline = pipeline {
          application = "foo"
          stage {
            type = stageWithSyntheticBefore.type
          }
        }
        val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

        beforeGroup {
          whenever(repository.retrievePipeline(message.executionId)) doReturn pipeline
        }

        action("the handler receives a message") {
          subject.handle(message)
        }

        afterGroup(::resetMocks)

        it("attaches the synthetic stage to the pipeline") {
          argumentCaptor<Stage<Pipeline>>().apply {
            verify(repository, times(2)).addStage(capture())
            allValues.map { it.id } shouldEqual listOf("${message.stageId}-1-pre1", "${message.stageId}-2-pre2")
          }
        }

        it("raises an event to indicate the synthetic stage is starting") {
          verify(queue).push(StartStage(
            message.executionType,
            message.executionId,
            "foo",
            pipeline.stages.first().id
          ))
        }
      }

      context("after the main stage") {
        val pipeline = pipeline {
          application = "foo"
          stage {
            type = stageWithSyntheticAfter.type
          }
        }
        val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

        beforeGroup {
          whenever(repository.retrievePipeline(message.executionId)) doReturn pipeline
        }

        afterGroup(::resetMocks)

        action("the handler receives a message") {
          subject.handle(message)
        }

        it("attaches the synthetic stage to the pipeline") {
          argumentCaptor<Stage<Pipeline>>().apply {
            verify(repository, times(2)).addStage(capture())
            allValues.map { it.id } shouldEqual listOf("${message.stageId}-2-post2", "${message.stageId}-1-post1")
          }
        }

        it("raises an event to indicate the first task is starting") {
          verify(queue).push(StartTask(
            message.executionType,
            message.executionId,
            "foo",
            message.stageId,
            "1"
          ))
        }
      }
    }

    context("with other upstream stages that are incomplete") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          refId = "1"
          status = SUCCEEDED
          type = singleTaskStage.type
        }
        stage {
          refId = "2"
          status = RUNNING
          type = singleTaskStage.type
        }
        stage {
          refId = "3"
          requisiteStageRefIds = setOf("1", "2")
          type = singleTaskStage.type
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stageByRef("3").id)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("doesn't build its tasks") {
        pipeline.stageByRef("3").tasks shouldMatch isEmpty
      }

      it("waits for the other upstream stage to complete") {
        verify(queue, never()).push(isA<StartTask>())
      }

      it("does not publish an event") {
        verifyZeroInteractions(publisher)
      }
    }

    context("with an execution window") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          type = stageWithSyntheticBefore.type
          context["restrictExecutionDuringTimeWindow"] = true
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("injects a 'wait for execution window' stage before any other synthetic stages") {
        argumentCaptor<Stage<Pipeline>>().apply {
          verify(repository, times(3)).addStage(capture())
          firstValue.type shouldEqual RestrictExecutionDuringTimeWindow.TYPE
          firstValue.parentStageId shouldEqual message.stageId
          firstValue.syntheticStageOwner shouldEqual STAGE_BEFORE
        }
      }

      it("starts the 'wait for execution window' stage") {
        verify(queue).push(check<StartStage> {
          it.stageId shouldEqual pipeline.stages.find { it.type == RestrictExecutionDuringTimeWindow.TYPE }!!.id
        })
      }
    }
  }

  describe("running a branching stage") {
    context("when the stage starts") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          refId = "1"
          name = "parallel"
          type = stageWithParallelBranches.type
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stageByRef("1").id)

      beforeGroup {
        whenever(repository.retrievePipeline(pipeline.id)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("builds tasks for the main branch") {
        val stage = pipeline.stageById(message.stageId)
        stage.tasks.map(Task::getName) shouldEqual listOf("post-branch")
      }

      it("builds synthetic stages for each parallel branch") {
        pipeline.stages.size shouldEqual 4
        assertThat(
          pipeline.stages.map { it.type },
          allElements(equalTo(stageWithParallelBranches.type))
        )
        // TODO: contexts, etc.
      }

      it("renames the primary branch") {
        pipeline.stageByRef("1").name shouldEqual "is parallel"
      }

      it("renames each parallel branch") {
        val stage = pipeline.stageByRef("1")
        pipeline.stages.filter { it.parentStageId == stage.id }.map { it.name } shouldEqual listOf("run in us-east-1", "run in us-west-2", "run in eu-west-1")
      }

      it("runs the parallel stages") {
        verify(queue, times(3)).push(check<StartStage> {
          pipeline.stageById(it.stageId).parentStageId shouldEqual message.stageId
        })
      }
    }

    context("when one branch starts") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          refId = "1"
          name = "parallel"
          type = stageWithParallelBranches.type
          stageWithParallelBranches.buildSyntheticStages(this)
          stageWithParallelBranches.buildTasks(this)
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages[0].id)

      beforeGroup {
        whenever(repository.retrievePipeline(pipeline.id)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("builds tasks for the branch") {
        val stage = pipeline.stageById(message.stageId)
        assertThat(stage.tasks, !isEmpty)
        stage.tasks.map(Task::getName) shouldEqual listOf("in-branch")
      }

      it("does not build more synthetic stages") {
        val stage = pipeline.stageById(message.stageId)
        pipeline.stages.map(Stage<Pipeline>::getParentStageId) shouldMatch !hasElement(stage.id)
      }
    }
  }

  describe("running a rolling push stage") {
    val pipeline = pipeline {
      application = "foo"
      stage {
        refId = "1"
        type = rollingPushStage.type
      }
    }

    context("when the stage starts") {
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stageByRef("1").id)

      beforeGroup {
        whenever(repository.retrievePipeline(pipeline.id)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("builds tasks for the main branch") {
        pipeline.stageById(message.stageId).let { stage ->
          stage.tasks.size shouldEqual 5
          stage.tasks[0].isLoopStart shouldEqual false
          stage.tasks[1].isLoopStart shouldEqual true
          stage.tasks[2].isLoopStart shouldEqual false
          stage.tasks[3].isLoopStart shouldEqual false
          stage.tasks[4].isLoopStart shouldEqual false
          stage.tasks[0].isLoopEnd shouldEqual false
          stage.tasks[1].isLoopEnd shouldEqual false
          stage.tasks[2].isLoopEnd shouldEqual false
          stage.tasks[3].isLoopEnd shouldEqual true
          stage.tasks[4].isLoopEnd shouldEqual false
        }
      }

      it("runs the parallel stages") {
        verify(queue).push(check<StartTask> {
          it.taskId shouldEqual "1"
        })
      }
    }
  }

  describe("running an optional stage") {
    context("if the stage should be run") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          refId = "1"
          type = stageWithSyntheticBefore.type
          context["stageEnabled"] = mapOf(
            "type" to "expression",
            "expression" to "true"
          )
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

      beforeGroup {
        whenever(repository.retrievePipeline(pipeline.id)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("proceeds with the first synthetic stage as normal") {
        verify(queue).push(any<StartStage>())
      }
    }

    context("if the stage should be skipped") {
      val pipeline = pipeline {
        application = "foo"
        stage {
          refId = "1"
          type = stageWithSyntheticBefore.type
          context["stageEnabled"] = mapOf(
            "type" to "expression",
            "expression" to "false"
          )
        }
      }
      val message = StartStage(Pipeline::class.java, pipeline.id, "foo", pipeline.stages.first().id)

      beforeGroup {
        whenever(repository.retrievePipeline(pipeline.id)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("skips the stage") {
        verify(queue).push(check<CompleteStage> {
          it.status shouldEqual SKIPPED
        })
      }

      it("doesn't build any tasks") {
        pipeline.stageById(message.stageId).tasks shouldMatch isEmpty
      }

      it("doesn't build any synthetic stages") {
        pipeline.stages.filter { it.parentStageId == message.stageId } shouldMatch isEmpty
      }
    }
  }

  describe("invalid commands") {

    val message = StartStage(Pipeline::class.java, "1", "foo", "1")

    describe("no such execution") {
      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId)) doThrow ExecutionNotFoundException("No Pipeline found for ${message.executionId}")
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("emits an error event") {
        verify(queue).push(isA<InvalidExecutionId>())
      }
    }

    describe("no such stage") {
      val pipeline = pipeline {
        id = message.executionId
        application = "foo"
      }

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("emits an error event") {
        verify(queue).push(isA<InvalidStageId>())
      }
    }
  }
})
