package info.kwarc.mmt.leo.AgentSystem.AndOrSystem.DataTreeSystem.PartitionImpl

import info.kwarc.mmt.leo.AgentSystem.AndOrSystem.DataTreeSystem.{DataTree, DataTreeSection}
import info.kwarc.mmt.leo.AgentSystem.AndOrSystem.PTApplicability
import info.kwarc.mmt.leo.AgentSystem.{Task, Section}

/**
 * Created by Mark on 7/21/2015.
 */
class PartitionTask(nodeVar: DataTree[Int], agent: PartitionAgent) extends Task with PTApplicability {
  val name = "Partition Task"
  val sentBy = agent
  val node = nodeVar
  val section = agent.blackboard.get.proofTreeSection

  override def toString:String ={
    "Task writeSet:" + setDisplay(writeSet(section)) 
  }

  def execute():Boolean = {
    if (!this.isApplicable(agent.blackboard.get)) {return false}
    log("executing: "+ this,3)
    log("TREE BEFORE: " + addIndent(agent.blackboard.get.proofTree.toString),2)
    section.update(node,{n=>n.conj=false;n})
    addBranches()
    log("TREE AFTER: " + addIndent(agent.blackboard.get.proofTree.toString),2)
    true
  }

  //TODO try to avoid reflection
  override def readSet(s: Section): Set[s.ObjectType] = {
    if (s==section){return Set(node.asInstanceOf[s.ObjectType])}
    Set.empty[s.ObjectType]
  }

  override def writeSet(s: Section):Set[s.ObjectType] = {
    if (s==section){return Set(node.asInstanceOf[s.ObjectType])}
    Set.empty[s.ObjectType]
  }

  var isExpansion = true
  val allNumbers = agent.numbers

  def usableNumbers = allNumbers.filter(_ <= node.data)

  def addBranches(): Unit = {
    usableNumbers.foreach(
      {int=>
        val add = new DataTree(node.data - int,conjVar=false,None)
        section.addTree(node,add) //TODO make sure this plays well with messages
        val min: Int = usableNumbers.min
        if (add.data - int == 0) {
          add.setSat(true)
          add.percolate() //TODO go through section interface
          return
        }else if  (add.data < min) {
          add.setSat(false)
          add.percolate() //TODO go through section interface
        }
      }
    )
  }


}