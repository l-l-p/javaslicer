/** License information:
 *    Component: javaslicer-core
 *    Package:   de.unisb.cs.st.javaslicer.instructionSimulation
 *    Class:     SimpleVariableUsage
 *    Filename:  javaslicer-core/src/main/java/de/unisb/cs/st/javaslicer/instructionSimulation/SimpleVariableUsage.java
 *
 * This file is part of the JavaSlicer tool, developed by Clemens Hammacher at Saarland University.
 * See http://www.st.cs.uni-saarland.de/javaslicer/ for more information.
 *
 * This work is licensed under the Creative Commons Attribution-ShareAlike 3.0 Unported License.
 * To view a copy of this license, visit http://creativecommons.org/licenses/by-sa/3.0/ or send a
 * letter to Creative Commons, 171 Second Street, Suite 300, San Francisco, California, 94105, USA.
 */
package de.unisb.cs.st.javaslicer.instructionSimulation;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import de.unisb.cs.st.javaslicer.variables.Variable;

public class SimpleVariableUsage implements DynamicInformation {

    private final Collection<? extends Variable> usedVariables;
    private final Collection<? extends Variable> definedVariables;
    private final boolean isCatchBlock;
    private final Map<Long, Collection<? extends Variable>> createdObjects;

    public SimpleVariableUsage(final Collection<? extends Variable> usedVariables,
            final Collection<? extends Variable> definedVariables) {
        this(usedVariables, definedVariables, Collections.<Long, Collection<? extends Variable>>emptyMap());
    }
    public SimpleVariableUsage(final Collection<? extends Variable> usedVariables,
            final Collection<? extends Variable> definedVariables,
            final Map<Long, Collection<? extends Variable>> createdObjects) {
        this(usedVariables, definedVariables, false, createdObjects);
    }

    public SimpleVariableUsage(final Collection<? extends Variable> usedVariables,
            final Collection<? extends Variable> definedVariables, final boolean isCatchBlock) {
        this(usedVariables, definedVariables, isCatchBlock, Collections.<Long, Collection<? extends Variable>>emptyMap());
    }
    public SimpleVariableUsage(final Collection<? extends Variable> usedVariables,
            final Collection<? extends Variable> definedVariables, final boolean isCatchBlock,
            final Map<Long, Collection<? extends Variable>> createdObjects) {
        this.usedVariables = usedVariables;
        this.definedVariables = definedVariables;
        this.isCatchBlock = isCatchBlock;
        this.createdObjects = createdObjects;
    }

    public SimpleVariableUsage(final Variable usedVariable,
            final Variable definedVariable) {
        this(Collections.singleton(usedVariable), Collections.singleton(definedVariable));
    }

    public SimpleVariableUsage(final Collection<? extends Variable> usedVariables, final Variable definedVariable) {
        this(usedVariables, Collections.singleton(definedVariable));
    }

    public SimpleVariableUsage(final Variable usedVariable, final Collection<? extends Variable> definedVariables) {
        this(Collections.singleton(usedVariable), definedVariables);
    }

    @Override
	public Collection<? extends Variable> getUsedVariables() {
        return this.usedVariables;
    }

    @Override
	public Collection<? extends Variable> getDefinedVariables() {
        return this.definedVariables;
    }

    @Override
	public boolean isCatchBlock() {
        return this.isCatchBlock;
    }

    @Override
	public Collection<? extends Variable> getUsedVariables(final Variable definedVariable) {
        return this.usedVariables;
    }

    @Override
    public String toString() {
        return "used:    "+getUsedVariables()+System.getProperty("line.separator")
            +"defined: "+getDefinedVariables();
    }
    @Override
	public Map<Long, Collection<? extends Variable>> getCreatedObjects() {
        return this.createdObjects;
    }

}
